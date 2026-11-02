// S6 조회 섞은 부하. S1 쓰기(RATE, 기본 70)를 고정하고 GET을 GET_RATE(요청/초)로 얹는다.
// GET 구성은 주문 단건 40%, 결제 상태 40%, 단말 메뉴(ETag 304) 15%, 점주 주문 목록 5%다(spec 2026-11-02-query-measurement 결정 1).
// 준비 파일(s6-prepare.js)과 주문 id 파일(export-read-ids.sql)을 읽는다. 실행 방법은 load-test/README.md.
import { ownerLogin, provisionTerminals, readMenu, readOrder, readOwnerOrders, readPaymentAttempts } from './lib/api.js';
import { baselineScenarios } from './lib/baseline.js';

export { syncSale, httpPair } from './lib/baseline.js';

const prepared = JSON.parse(open(__ENV.PREPARED || 'results/s6-prepared.json'));
const orderIds = JSON.parse(open(__ENV.ORDER_IDS || 'results/s6-order-ids.json'));
const GET_RATE = Number(__ENV.GET_RATE || 0);
// 단건·결제 상태 중 최근 1일 주문의 비율. 나머지는 90일 무작위 주문이다.
const RECENT_SHARE = 0.9;
// 쓰기 VU(기본 400)와 합쳐 단말 수(1,000)를 넘지 않게 한다. 단말 분배(ownedTerminals)가 모든 시나리오의 VU 수로 나누기 때문이다.
const GET_VUS = Number(__ENV.GET_VUS || 200);

function getScenario(exec, share) {
  return {
    executor: 'constant-arrival-rate', exec, rate: Math.max(1, Math.round(GET_RATE * share)), timeUnit: '1s',
    duration: __ENV.DURATION || '2m', preAllocatedVUs: Math.round(GET_VUS * share), maxVUs: Math.max(1, Math.round(GET_VUS * share)),
  };
}

const scenarios = baselineScenarios();
if (GET_RATE > 0) {
  scenarios.order_get = getScenario('orderGet', 0.4);
  scenarios.payment_attempts_get = getScenario('paymentAttemptsGet', 0.4);
  scenarios.menu_get = getScenario('menuGet', 0.15);
  scenarios.owner_orders_get = getScenario('ownerOrdersGet', 0.05);
}

export const options = {
  setupTimeout: '10m',
  scenarios,
  // summary에 항목별 수치가 나오게 하는 표시용 기준이다. 판정은 spec의 판정 규칙이 한다.
  thresholds: {
    'http_req_duration{name:sync}': ['p(95)>=0'],
    'http_req_duration{name:order_create}': ['p(95)>=0'],
    'http_req_duration{name:order_checkout}': ['p(95)>=0'],
    'http_req_duration{name:order_get}': ['p(95)>=0'],
    'http_req_duration{name:payment_attempts_get}': ['p(95)>=0'],
    'http_req_duration{name:menu_get}': ['p(95)>=0'],
    'http_req_duration{name:owner_orders_get}': ['p(95)>=0'],
    'http_reqs{status:429}': ['count>=0'],
    'http_reqs{status:500}': ['count>=0'],
    sync_gap_items: ['count>=0'],
  },
};

// 쓰기는 S1처럼 실행마다 새 단말을 쓴다. 준비한 단말을 다시 쓰면 서버 watermark는 남고 k6 순번은 1부터라 sync가 CONFLICT가 된다.
// 그래서 GET과 쓰기는 같은 단말·매장 bucket을 나눠 쓰지 않는다(운영에서는 나눠 쓴다).
export function setup() {
  return { terminals: provisionTerminals(Number(__ENV.STORES || 250)), readTerminals: prepared.terminals, session: ownerLogin(prepared.credentials) };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

// GET은 순번이 없어 단말 분배와 관계없이 전체 단말에서 고른다.
function terminalAndOrder(data) {
  const terminal = pick(data.readTerminals);
  const ids = orderIds[terminal.storeId];
  return { terminal, orderId: pick(Math.random() < RECENT_SHARE ? ids.recent : ids.old) };
}

export function orderGet(data) {
  const { terminal, orderId } = terminalAndOrder(data);
  readOrder(terminal, orderId);
}

export function paymentAttemptsGet(data) {
  const { terminal, orderId } = terminalAndOrder(data);
  readPaymentAttempts(terminal, orderId);
}

export function menuGet(data) {
  const terminal = pick(data.readTerminals);
  readMenu(terminal, prepared.menuEtags[terminal.storeId]);
}

export function ownerOrdersGet(data) {
  readOwnerOrders(data.session, pick(data.readTerminals).storeId);
}

