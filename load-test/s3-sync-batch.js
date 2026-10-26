// S3 sync 배치 동시 실행. 단말 TERMINALS대가 30분 단절 뒤 판매 BACKLOG_SALES건(op는 그 두 배)을 한꺼번에 보낸다.
// 배치 크기 BATCH로 나눠 outbox가 빌 때까지 보내고, 단말마다 시작부터 빌 때까지의 시간을 convergence_seconds에 남긴다.
// 요청마다 TIMEOUT까지 응답을 기다린다(시연 단말은 30s). 첫 요청의 전송 시각을 reconnectedAt으로 모든 배치에 싣는다.
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { provisionTerminals, TERMINALS_PER_STORE, newOutbox, enqueueSales, flush } from './lib/api.js';

const TERMINALS = Number(__ENV.TERMINALS || 100);
const BACKLOG_SALES = Number(__ENV.BACKLOG_SALES || 250);
const BATCH = Number(__ENV.BATCH || 500);
const TIMEOUT = __ENV.TIMEOUT || '60s';
const OFFLINE_MINUTES = 30;
// 실패한 배치는 종류와 관계없이 jitter 없이 5초 뒤 같은 항목으로 다시 보낸다(시연 단말은 RATE_LIMITED면 Retry-After를 따른다, capacity.md). 이만큼 이어서 실패하면 그 단말은 수렴하지 못한 것으로 센다.
const MAX_CONSECUTIVE_FAILURES = 5;

const convergence = new Trend('convergence_seconds');
const unconverged = new Counter('unconverged_terminals');
const failedBatches = new Counter('failed_batches');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    reconnect: { executor: 'per-vu-iterations', vus: TERMINALS, iterations: 1, maxDuration: '15m' },
  },
  thresholds: {
    convergence_seconds: ['max>=0'],
    'http_req_duration{name:sync}': ['p(95)>=0'],
    unconverged_terminals: ['count>=0'],
    failed_batches: ['count>=0'],
    sync_gap_items: ['count>=0'],
  },
};

export function setup() {
  return { terminals: provisionTerminals(Math.ceil(TERMINALS / TERMINALS_PER_STORE)) };
}

export default function (data) {
  const outbox = newOutbox(data.terminals[exec.vu.idInTest - 1]);
  enqueueSales(outbox, BACKLOG_SALES, new Date(Date.now() - OFFLINE_MINUTES * 60 * 1000).toISOString());
  const started = Date.now();
  outbox.reconnectedAt = new Date(started).toISOString();
  let failures = 0;
  while (outbox.pending.length > 0) {
    if (flush(outbox, BATCH, { batch: String(BATCH) }, TIMEOUT)) {
      failures = 0;
      continue;
    }
    failedBatches.add(1);
    if (++failures >= MAX_CONSECUTIVE_FAILURES) {
      unconverged.add(1);
      return;
    }
    sleep(5);
  }
  convergence.add((Date.now() - started) / 1000);
}
