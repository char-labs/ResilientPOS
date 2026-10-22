// S4 느린 provider. 앱을 FAKE_GATEWAY_LATENCY로 띄운 뒤 판매를 초당 SALES_PER_SECOND건 보낸다.
// 판매의 SAMPLE 비율은 결제 시도 조회를 폴링해 sync 응답부터 APPROVED까지의 시간을 checkout_to_approved_seconds에 남긴다.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, provisionTerminals, terminalHeaders, newOutbox, enqueueSales, flush, ownedTerminals } from './lib/api.js';

const STORES = Number(__ENV.STORES || 250);
const SALES_PER_SECOND = Number(__ENV.SALES_PER_SECOND || 50);
const DURATION = __ENV.DURATION || '3m';
const SAMPLE = Number(__ENV.SAMPLE || 0.1);
// 폴링하는 VU는 그동안 다른 판매를 못 보내므로 넉넉히 둔다. 단말 수(STORES × 4)보다 크면 맡을 단말이 없는 VU가 생긴다.
const VUS = Number(__ENV.VUS || 900);
const POLL_INTERVAL_SECONDS = 0.5;
const POLL_LIMIT_SECONDS = 180;

const approvedIn = new Trend('checkout_to_approved_seconds');
const notApproved = new Counter('not_approved_within_limit');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    sales: {
      executor: 'constant-arrival-rate', rate: SALES_PER_SECOND, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: VUS, maxVUs: VUS,
      // 폴링 한도(POLL_LIMIT_SECONDS)보다 길게 둔다. 기본값(30초)이면 승인이 늦은 판매의 폴링이 기록 전에 끊긴다.
      gracefulStop: `${POLL_LIMIT_SECONDS + 20}s`,
    },
  },
  thresholds: {
    checkout_to_approved_seconds: ['p(95)>=0'],
    not_approved_within_limit: ['count>=0'],
    'http_req_duration{name:sync}': ['p(95)>=0'],
    sync_gap_items: ['count>=0'],
  },
};

export function setup() {
  return { terminals: provisionTerminals(STORES) };
}

let outboxes;
let cursor = 0;

export default function (data) {
  if (!outboxes) outboxes = ownedTerminals(data.terminals).map(newOutbox);
  if (outboxes.length === 0) throw new Error('이 VU에 맡길 단말이 없다. STORES를 늘리거나 VUS를 줄인다');
  const outbox = outboxes[cursor++ % outboxes.length];
  enqueueSales(outbox, 1, new Date().toISOString());
  const result = flush(outbox, 500, {});
  if (!result || Math.random() >= SAMPLE) return;

  // REQUEST_CHECKOUT 항목이 그 판매의 orderId를 돌려준다.
  const orderId = result.items[result.items.length - 1].orderId;
  const started = Date.now();
  while ((Date.now() - started) / 1000 < POLL_LIMIT_SECONDS) {
    const res = http.get(`${BASE_URL}/v1/orders/${orderId}/payment-attempts`, { headers: terminalHeaders(outbox.terminal), tags: { name: 'payment_attempts' } });
    const attempts = res.status === 200 ? res.json('data') : [];
    if (attempts.some((a) => a.status === 'APPROVED')) {
      approvedIn.add((Date.now() - started) / 1000);
      return;
    }
    sleep(POLL_INTERVAL_SECONDS);
  }
  notApproved.add(1);
}
