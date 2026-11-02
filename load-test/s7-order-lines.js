// S7 주문 line 개수별 HTTP 판매. 주문 생성(line LINES개)과 checkout 쌍을 초당 RATE번 보낸다.
// 새 행마다 나가는 merge SELECT(spec 2026-10-27-jpa-repository-adapters 결정 2)의 비용을 재는 A/B용이다(spec 2026-11-02-query-measurement 결정 4).
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, UNIT_PRICE, provisionTerminals, terminalHeaders, uuidv7 } from './lib/api.js';

const LINES = Number(__ENV.LINES || 1);
const RATE = Number(__ENV.RATE || 20);
// 단말 bucket(초당 2)을 넘지 않게 단말을 넉넉히 둔다. 판매 한 번이 요청 둘이다.
const STORES = Number(__ENV.STORES || 25);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    order_lines: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 100, maxVUs: 100,
    },
  },
  thresholds: {
    'http_req_duration{name:order_create}': ['p(95)>=0'],
    'http_req_duration{name:order_checkout}': ['p(95)>=0'],
  },
};

export function setup() {
  return { terminals: provisionTerminals(STORES) };
}

const lines = Array.from({ length: LINES }, (_, i) => ({ productCode: `SKU-${i}`, productName: '아메리카노', unitPrice: UNIT_PRICE, quantity: 1 }));

export default function (data) {
  const terminal = data.terminals[Math.floor(Math.random() * data.terminals.length)];
  const body = { storeId: terminal.storeId, terminalId: terminal.terminalId, clientOrderId: uuidv7(), currency: 'KRW', lines };
  const created = http.post(`${BASE_URL}/v1/orders`, JSON.stringify(body), { headers: terminalHeaders(terminal), tags: { name: 'order_create' } });
  if (!check(created, { 'order 201': (r) => r.status === 201 })) return;
  const checkout = http.post(`${BASE_URL}/v1/orders/${created.json('data.orderId')}/checkout`, null, { headers: terminalHeaders(terminal), tags: { name: 'order_checkout' } });
  check(checkout, { 'checkout 200': (r) => r.status === 200 });
}
