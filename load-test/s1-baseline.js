// S1 기준 혼합 부하. RATE(요청/초)를 DURATION 동안 유지한다. 단계마다 따로 실행해 단계별 원시 수치를 얻는다.
import { provisionTerminals } from './lib/api.js';
import { baselineScenarios } from './lib/baseline.js';

export { syncSale, httpPair, abusiveSync } from './lib/baseline.js';

const STORES = Number(__ENV.STORES || 250);

export const options = {
  setupTimeout: '10m',
  scenarios: baselineScenarios(),
  // 제출 형식(항목 하나의 수치)으로 summary에 나오도록 하는 표시용 기준이다. 판정은 spec의 판정 규칙이 한다.
  thresholds: {
    'http_req_duration{name:sync}': ['p(95)>=0'],
    'http_req_duration{name:order_create}': ['p(95)>=0'],
    'http_req_duration{name:order_checkout}': ['p(95)>=0'],
    'http_req_duration{traffic:normal}': ['p(95)>=0'],
    'http_req_failed{name:sync}': ['rate>=0'],
    sync_gap_items: ['count>=0'],
  },
};

export function setup() {
  return { terminals: provisionTerminals(STORES) };
}
