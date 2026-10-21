// 기준 혼합 부하(S1). 요청의 80%는 판매 1건짜리 sync 배치, 20%는 HTTP 주문 생성과 checkout 쌍이다.
// HTTP 쌍은 한 iteration이 요청 둘이므로 iteration 비율은 sync 0.8R, HTTP 0.1R이고 판매는 초당 0.9R건이다.
import { sleep } from 'k6';
import { newOutbox, enqueueSales, flush, httpSale, ownedTerminals } from './api.js';

export const RATE = Number(__ENV.RATE || 100);
export const DURATION = __ENV.DURATION || '2m';
// #87 상한 검증용. 오작동 단말 ABUSIVE대가 단말마다 초당 ABUSIVE_RATE번 sync를 보낸다. VU 하나가 단말 하나다.
export const ABUSIVE = Number(__ENV.ABUSIVE || 0);
const ABUSIVE_RATE = Number(__ENV.ABUSIVE_RATE || 2);
const SYNC_VUS = Number(__ENV.SYNC_VUS || 300);
const HTTP_VUS = Number(__ENV.HTTP_VUS || 100);

export function baselineScenarios() {
  const scenarios = {
    sync_sale: {
      executor: 'constant-arrival-rate', exec: 'syncSale', rate: Math.round(RATE * 0.8), timeUnit: '1s',
      duration: DURATION, preAllocatedVUs: SYNC_VUS, maxVUs: SYNC_VUS,
    },
    http_sale: {
      executor: 'constant-arrival-rate', exec: 'httpPair', rate: Math.max(1, Math.round(RATE * 0.1)), timeUnit: '1s',
      duration: DURATION, preAllocatedVUs: HTTP_VUS, maxVUs: HTTP_VUS,
    },
  };
  if (ABUSIVE > 0) {
    scenarios.abusive_sync = { executor: 'constant-vus', exec: 'abusiveSync', vus: ABUSIVE, duration: DURATION };
  }
  return scenarios;
}

let outboxes;
let cursor = 0;

function myOutboxes(data) {
  if (!outboxes) outboxes = ownedTerminals(data.terminals).map(newOutbox);
  if (outboxes.length === 0) throw new Error('이 VU에 맡길 단말이 없다. STORES를 늘리거나 VU 수를 줄인다');
  return outboxes;
}

export function syncSale(data) {
  const mine = myOutboxes(data);
  const outbox = mine[cursor++ % mine.length];
  enqueueSales(outbox, 1, new Date().toISOString());
  flush(outbox, 500, { traffic: 'normal' });
}

export function httpPair(data) {
  const mine = myOutboxes(data);
  httpSale(mine[cursor++ % mine.length].terminal);
}

export function abusiveSync(data) {
  const outbox = myOutboxes(data)[0];
  const started = Date.now();
  enqueueSales(outbox, 1, new Date().toISOString());
  flush(outbox, 500, { traffic: 'abusive' });
  sleep(Math.max(0, 1 / ABUSIVE_RATE - (Date.now() - started) / 1000));
}
