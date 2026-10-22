// 시나리오들이 함께 쓰는 준비 단계와 요청. 실행 방법은 load-test/README.md.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const TERMINALS_PER_STORE = 4;
// 끝자리 0이라 가짜 provider가 승인한다(FakePaymentGateway).
export const UNIT_PRICE = '4500';

const JSON_HEADERS = { 'Content-Type': 'application/json' };
// 단말 순번이 끊긴 항목. 0이 아니면 스크립트가 순번을 잘못 이어 보낸 것이라 그 실행은 버린다.
export const syncGaps = new Counter('sync_gap_items');

// RFC 9562 UUID v7. 서버도 시간순 UUID를 쓰므로 단말처럼 만든다.
export function uuidv7() {
  const hex = Date.now().toString(16).padStart(12, '0');
  let rest = '';
  for (let i = 0; i < 20; i++) rest += Math.floor(Math.random() * 16).toString(16);
  const variant = ((parseInt(rest[3], 16) & 0x3) | 0x8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-7${rest.slice(0, 3)}-${variant}${rest.slice(4, 7)}-${rest.slice(7, 19)}`;
}

function cookieValue(res, name) {
  const found = res.cookies[name];
  if (!found || found.length === 0) fail(`${res.request.url} 응답에 ${name} 쿠키가 없다 (status ${res.status})`);
  return found[0].value;
}

// 점주 쿠키는 __Host- 접두어라 Secure다. k6 쿠키 저장소는 HTTP에서 Secure 쿠키를 보내지 않으므로 Cookie 헤더로 직접 싣는다.
function ownerHeaders(session) {
  const cookies = [`__Host-XSRF-TOKEN=${session.xsrf}`];
  if (session.id) cookies.push(`__Host-SESSION=${session.id}`);
  return { ...JSON_HEADERS, 'X-XSRF-TOKEN': session.xsrf, Cookie: cookies.join('; ') };
}

function ownerPost(session, path, body) {
  const res = http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers: ownerHeaders(session), jar: new http.CookieJar(), tags: { name: 'setup' } });
  if (res.status >= 300) fail(`${path} 실패: ${res.status} ${res.body}`);
  return res;
}

// 점주 하나가 매장 stores곳을 열고 매장마다 단말 4대를 등록한다. 실행마다 새 이메일이라 이전 실행의 데이터와 겹치지 않는다.
export function provisionTerminals(stores) {
  const csrf = http.get(`${BASE_URL}/owner/v1/csrf`, { tags: { name: 'setup' } });
  const session = { xsrf: cookieValue(csrf, '__Host-XSRF-TOKEN') };
  const credentials = { email: `load-${uuidv7()}@example.com`, password: 'load-test-password-0001' };
  ownerPost(session, '/owner/v1/signup', credentials);
  const login = ownerPost(session, '/owner/v1/login', credentials);
  session.id = cookieValue(login, '__Host-SESSION');
  session.xsrf = cookieValue(login, '__Host-XSRF-TOKEN');

  const terminals = [];
  for (let s = 0; s < stores; s++) {
    const storeId = ownerPost(session, '/owner/v1/stores', { name: `load-store-${s}` }).json('data.storeId');
    for (let t = 0; t < TERMINALS_PER_STORE; t++) {
      const data = ownerPost(session, `/owner/v1/stores/${storeId}/terminals`, { terminalCode: `T${t}` }).json('data');
      terminals.push({ storeId, terminalId: data.terminalId, token: data.token });
    }
  }
  return terminals;
}

export function terminalHeaders(terminal) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${terminal.token}` };
}

// 판매 1건 = 주문 생성과 결제 요청. createdAt은 #76의 sync_convergence_seconds가 쓴다(그 전에는 서버가 무시한다).
export function saleOperations(terminal, firstSequence, createdAt) {
  const clientOrderId = uuidv7();
  return [
    {
      kind: 'CREATE_ORDER', operationId: uuidv7(), sequence: firstSequence, createdAt,
      order: {
        storeId: terminal.storeId, clientOrderId, currency: 'KRW',
        lines: [{ productCode: 'SKU-1', productName: '아메리카노', unitPrice: UNIT_PRICE, quantity: 1 }],
      },
    },
    { kind: 'REQUEST_CHECKOUT', operationId: uuidv7(), sequence: firstSequence + 1, createdAt, clientOrderId, amount: UNIT_PRICE },
  ];
}

// 단말 하나의 outbox. 확정된 순번(lastAckedSequence)까지만 지우고 나머지는 다음 요청에 다시 보낸다(시연 단말과 같은 규약).
export function newOutbox(terminal) {
  return { terminal, nextSequence: 1, acked: 0, pending: [] };
}

export function enqueueSales(outbox, sales, createdAt) {
  for (let i = 0; i < sales; i++) {
    outbox.pending.push(...saleOperations(outbox.terminal, outbox.nextSequence, createdAt));
    outbox.nextSequence += 2;
  }
}

export function flush(outbox, batchSize, tags) {
  const operations = outbox.pending.slice(0, batchSize);
  const res = http.post(
    `${BASE_URL}/v1/terminals/${outbox.terminal.terminalId}/sync`,
    JSON.stringify({ operations }),
    { headers: terminalHeaders(outbox.terminal), tags: { name: 'sync', ...tags } },
  );
  if (!check(res, { 'sync 200': (r) => r.status === 200 })) return null;
  const data = res.json('data');
  // 확정 순번을 믿고 outbox를 지우므로 응답이 규약에 맞는지 먼저 본다. 어긋나면 그 실행은 버린다(README).
  check(data, {
    'sync 항목 수가 보낸 수와 같다': (d) => d.items.length === operations.length,
    'sync 확정 순번이 뒤로 가지 않고 매긴 순번 안이다': (d) => d.lastAckedSequence >= outbox.acked && d.lastAckedSequence < outbox.nextSequence,
    'sync 항목에 CONFLICT·REJECTED가 없다': (d) => d.items.every((item) => item.status !== 'CONFLICT' && item.status !== 'REJECTED'),
  });
  outbox.acked = Math.max(outbox.acked, data.lastAckedSequence);
  outbox.pending = outbox.pending.filter((op) => op.sequence > outbox.acked);
  syncGaps.add(data.items.filter((item) => item.status === 'GAP').length);
  return data;
}

// HTTP 경로의 판매 한 건. 외부 연동처럼 단말이 바로 주문을 만들고 결제를 요청한다.
export function httpSale(terminal) {
  const body = {
    storeId: terminal.storeId, terminalId: terminal.terminalId, clientOrderId: uuidv7(), currency: 'KRW',
    lines: [{ productCode: 'SKU-1', productName: '아메리카노', unitPrice: UNIT_PRICE, quantity: 1 }],
  };
  const created = http.post(`${BASE_URL}/v1/orders`, JSON.stringify(body), { headers: terminalHeaders(terminal), tags: { name: 'order_create' } });
  if (!check(created, { 'order 201': (r) => r.status === 201 })) return;
  const orderId = created.json('data.orderId');
  const checkout = http.post(`${BASE_URL}/v1/orders/${orderId}/checkout`, null, { headers: terminalHeaders(terminal), tags: { name: 'order_checkout' } });
  check(checkout, { 'checkout 200': (r) => r.status === 200 });
}

// VU마다 단말을 겹치지 않게 나눠 가진다. 한 단말의 sync를 두 VU가 동시에 보내면 순번이 엉킨다.
// VU 번호는 시나리오를 가로질러 1부터 매겨지므로 모든 시나리오의 VU 수 합으로 나눈다.
export function ownedTerminals(terminals) {
  const totalVus = Object.values(exec.test.options.scenarios).reduce((sum, s) => sum + (s.maxVUs || s.vus || 0), 0);
  const vu = exec.vu.idInTest - 1;
  return terminals.filter((_, i) => i % totalVus === vu);
}
