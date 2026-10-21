// S2 같은 키 주문 생성 경합. S1 부하(RATE) 위에서 같은 (terminalId, clientOrderId)의 POST /v1/orders를 CONTENDERS개 동시에 보낸다.
// 앞 요청이 락을 쥔 동안 나머지는 기다렸다가 앞 주문을 201로 받는다. fail-open이면 늦은 쪽이 커밋에서 409 CONCURRENT_REQUEST다.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, UNIT_PRICE, provisionTerminals, terminalHeaders, uuidv7 } from './lib/api.js';
import { baselineScenarios, DURATION } from './lib/baseline.js';

export { syncSale, httpPair } from './lib/baseline.js';

const STORES = Number(__ENV.STORES || 250);
const CONTENDERS = Number(__ENV.CONTENDERS || 5);
const BURSTS_PER_SECOND = Number(__ENV.BURSTS_PER_SECOND || 2);
const BURST_VUS = 20;

export const options = {
  setupTimeout: '10m',
  // http.batch가 한 묶음을 한꺼번에 보내게 한다. 기본값(호스트당 6)이면 6개씩 나뉘어 동시성이 깨진다.
  batch: CONTENDERS,
  batchPerHost: CONTENDERS,
  scenarios: {
    ...baselineScenarios(),
    contention: {
      executor: 'constant-arrival-rate', exec: 'burst', rate: BURSTS_PER_SECOND, timeUnit: '1s',
      duration: DURATION, preAllocatedVUs: BURST_VUS, maxVUs: BURST_VUS,
    },
  },
  thresholds: {
    'http_req_duration{name:order_create_contended}': ['p(95)>=0'],
    'http_reqs{name:order_create_contended,status:201}': ['count>=0'],
    'http_reqs{name:order_create_contended,status:409}': ['count>=0'],
    'http_req_duration{name:sync}': ['p(95)>=0'],
    'http_req_duration{name:order_create}': ['p(95)>=0'],
    sync_gap_items: ['count>=0'],
  },
};

export function setup() {
  return { terminals: provisionTerminals(STORES) };
}

export function burst(data) {
  // HTTP 주문은 단말 순번을 쓰지 않으므로 바탕 부하가 맡은 단말을 함께 써도 된다.
  const terminal = data.terminals[Math.floor(Math.random() * data.terminals.length)];
  const body = JSON.stringify({
    storeId: terminal.storeId, terminalId: terminal.terminalId, clientOrderId: uuidv7(), currency: 'KRW',
    lines: [{ productCode: 'SKU-1', productName: '아메리카노', unitPrice: UNIT_PRICE, quantity: 1 }],
  });
  const request = ['POST', `${BASE_URL}/v1/orders`, body, { headers: terminalHeaders(terminal), tags: { name: 'order_create_contended' } }];
  const responses = http.batch(Array.from({ length: CONTENDERS }, () => request));
  const orderIds = new Set(responses.filter((r) => r.status === 201).map((r) => r.json('data.orderId')));
  check(responses, { '같은 키의 201은 모두 같은 주문': () => orderIds.size <= 1 });
}
