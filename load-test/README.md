# 부하 측정(k6)

용량 상한을 재는 k6 스크립트다. 무엇을 왜 재는지와 판정 규칙, 결과는 [k6-capacity-measurement spec](../docs/spec/2026-10-21-k6-capacity-measurement.md), 채택한 값과 다시 재는 조건은 [용량 기준](../docs/wiki/capacity.md)에 있다. 수치는 운영 절대값이 아니라 같은 머신에서의 상대 비교다.

## 준비
- k6를 설치한다(`brew install k6`). 결과에는 `k6 version`을 적는다.
- 저장소 루트에서 앱과 수집기를 띄운다. compose 프로젝트 이름이 `resilientpos`로 고정이라 다른 worktree가 띄운 스택과 포트·볼륨을 함께 쓴다. 다른 작업이 쓰고 있지 않은지 먼저 확인한다.

```sh
docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build
```

- 환경을 기록한다. 결과 표 위에 그대로 붙인다.

```sh
git rev-parse --short HEAD; k6 version
sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize; orbctl config show | grep -E '^(cpu|memory_mib)'
docker compose -f docker/docker-compose.yaml images
```

- 앱을 띄우거나 설정을 바꿔 다시 띄울 때마다 JIT를 데운다: `k6 run -e RATE=50 -e DURATION=1m load-test/s1-baseline.js`. 데우지 않은 첫 실행은 지연이 몇 배로 나온다.
- 실행 사이에는 남은 외부화 작업이 빠질 때까지 기다린다. `PUBLISHED`·`PROCESSING`인 미완료 발행 기록 수(`select count(*) from event_publication where completion_date is null and status in ('PUBLISHED','PROCESSING')`)가 0 근처로 돌아오면 된다. 외부화 상한을 넘는 부하 뒤에는 수 분에서 20분 걸린다. 앱을 다시 띄우면 메모리의 실행기 큐가 사라져 그 기록들이 5분 뒤 `FAILED`가 되고 재전송이 다음 실행에 섞이므로, 빠진 뒤에 다시 띄운다.
- 다른 세션의 Gradle 테스트(Testcontainers)는 같은 CPU를 쓴다. 측정 구간과 겹치지 않게 시각을 맞춘다.
- 앱의 Spring 설정을 바꿔 띄울 때는 `docker/measure.env`(git이 추적하지 않음)에 환경 변수를 한 줄씩 넣고(예: `SPRING_TASK_EXECUTION_POOL_CORESIZE=32`, `SPRING_TASK_EXECUTION_POOL_QUEUECAPACITY=200`) `docker compose -f docker/docker-compose.yaml --profile app up -d app`으로 다시 띄운다. 끝나면 파일을 지우고 다시 띄운다.
- 측정 세션을 새로 시작할 때는 DB를 비운다. 발행 기록(`event_publication`)은 완료 모드 `update`·7일 보관이라 쌓이고, 백만 행을 넘으면 부하 중 autovacuum이 측정을 흔든다. 공유 스택이라 다른 작업이 쓰지 않을 때만 한다: `docker compose -f docker/docker-compose.yaml --profile app stop app && docker compose -f docker/docker-compose.yaml rm -sf postgres && docker volume rm resilientpos_postgres-data && docker compose -f docker/docker-compose.yaml up -d postgres`.

## 실행
모든 명령은 저장소 루트에서 실행한다. 결과 JSON은 `load-test/results/`(git이 추적하지 않음)에 남는다. 공통 인자는 `--summary-trend-stats "avg,min,med,p(90),p(95),p(99),max"`이고 아래에서는 `$STATS`로 줄인다.

```sh
STATS='--summary-trend-stats=avg,min,med,p(90),p(95),p(99),max'
mkdir -p load-test/results
```

| # | 시나리오 | 명령 |
|---|---|---|
| S1 | 기준 혼합. 단계마다 2분 | `for r in 50 100 200 300 400 500; do k6 run -e RATE=$r $STATS --summary-export load-test/results/s1-rate$r.json load-test/s1-baseline.js; sleep 45; done`. 외부화 상한을 넘는 단계 뒤에는 위의 잔여 작업 확인을 거친다 |
| S1 | #87 상한 검증. 오작동 단말 M대가 단말마다 초당 N번 | `k6 run -e RATE=<바탕 부하> -e ABUSIVE=<M> -e ABUSIVE_RATE=<N> $STATS --summary-export load-test/results/s1-abusive.json load-test/s1-baseline.js` |
| S1 | #87·#101 고장 단말. 단말 하나가 같은 sync 배치를 C개 동시에, 간격마다(기본 1s) | `k6 run -e RATE=<바탕 부하> -e TERMINAL_BURST=<C> -e BURST_EVERY=5s $STATS --summary-export load-test/results/s1-burst.json load-test/s1-baseline.js`. 빈도 상한이 있으면 간격을 빈 bucket이 차는 시간(capacity ÷ refill, 권장값 5s) 이상으로 줘야 묶음마다 가득 찬 bucket으로 보낸다 |
| S2 | 같은 키 경합. 동시 요청 수마다 | `for n in 2 5 10 20; do k6 run -e RATE=<바탕 부하> -e CONTENDERS=$n $STATS --summary-export load-test/results/s2-n$n.json load-test/s2-same-key.js; done` |
| S3 | sync 배치 동시 실행 | `for t in 1 5 10 20 50 100; do k6 run -e TERMINALS=$t $STATS --summary-export load-test/results/s3-t$t.json load-test/s3-sync-batch.js; done` 뒤 단말 100대에서 `-e BATCH=100`·`250`·`500` |
| S4 | 느린 provider. 지연마다 앱을 다시 띄운다 | `FAKE_GATEWAY_LATENCY=PT1S docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d app` 뒤 `k6 run -e SALES_PER_SECOND=20 -e DURATION=60s -e SAMPLE=0.2 -e VUS=300 -e STORES=100 $STATS --summary-export load-test/results/s4-1s.json load-test/s4-slow-provider.js`. 지연은 `PT0S`·`PT0.3S`·`PT1S`·`PT2.9S`. `PT3S`는 provider deadline(3초)과 같아 모든 승인이 결과 불명이 된다. 표본 판매의 폴링(0.5초 간격)이 부하에 더해지므로 승인 처리량은 서버 metric(lag, 처리율)으로 읽는다 |
| S5 | Kafka 60초 정지 | `RATE=<바탕 부하> load-test/s5-kafka-stop.sh` |

바탕 부하는 외부화가 버티는 수준의 70%로 둔다. 2026-10-21 측정에서는 HTTP가 약 300 RPS까지 버텼지만 외부화가 약 100 RPS에서 막혀 70을 썼다([용량 기준](../docs/wiki/capacity.md)). 외부화 상한을 넘는 바탕 부하는 실행마다 잔여 작업을 쌓아 다음 실행을 오염시킨다.

환경 변수: `BASE_URL`(기본 `http://localhost:8080`), `STORES`(매장 수, 매장마다 단말 4대, 기본 250), `RATE`(초당 요청), `DURATION`(기본 2분, S4는 3분), `SYNC_VUS`·`HTTP_VUS`(기본 300·100), `CONTENDERS`·`BURSTS_PER_SECOND`(S2), `TERMINAL_BURST`·`BURST_EVERY`·`BURST_SALES`(S1 고장 단말. `BURST_SALES=250`이면 첫 묶음에 500 op backlog를 쌓고 그 뒤로 같은 배치를 되풀이한다), `TERMINALS`·`BACKLOG_SALES`·`BATCH`·`TIMEOUT`(S3, 요청마다 응답을 기다리는 시간, 기본 `60s`. 기본값은 #77 측정과 같고, 단말 sync 계약으로 재려면 `-e BATCH=50 -e TIMEOUT=30s`), `SALES_PER_SECOND`·`SAMPLE`·`VUS`(S4).

`sync_gap_items`가 0이 아니거나 sync 응답 검사(`checks`의 항목 수, 확정 순번, CONFLICT·REJECTED)가 실패하면 스크립트가 단말 순번을 잘못 이은 것이라 그 실행은 버린다. `dropped_iterations`가 있으면 k6가 목표 도착률을 내지 못한 것이다. S1~S3에서는 앱이 포화한 것으로 읽는다(VU가 모두 응답을 기다린다). S4에서는 폴링하는 VU가 붙잡혀서도 생긴다.

## Grafana에서 읽을 값
Grafana(`localhost:3000`, 다른 프로세스가 IPv6 `::1:3000`을 쓰고 있으면 `127.0.0.1:3000`) Explore에서 Prometheus 데이터 소스로 측정 구간을 골라 읽는다. scrape 간격이 15초라 창은 1분 이상으로 둔다.

| 값 | PromQL |
|---|---|
| uri별 p95 | `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))` |
| 5xx 비율 | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m])) / sum(rate(http_server_requests_seconds_count[1m]))` |
| 커넥션 대기 | `max_over_time(hikaricp_connections_pending[1m])`, `max_over_time(hikaricp_connections_active[1m])` |
| consumer lag | `max by (client_id) (kafka_consumer_fetch_manager_records_lag_max)` |
| 실행기 큐 | `max_over_time(executor_queued_tasks{name="applicationTaskExecutor"}[1m])`, `increase(task_execution_rejected_total[1m])` |
| 힙 | `sum(jvm_memory_used_bytes{area="heap"})` |
| Redis 명령 p99·p99.9 | `histogram_quantile(0.999, sum by (le, db_operation) (rate(lettuce_seconds_bucket[1m])))` |
| 락 획득 시도(SET) 수 | `sum(rate(lettuce_seconds_count{db_operation="SET"}[1m]))` |
| 락 대기·보유 시간 | `histogram_quantile(0.999, sum by (le, lock) (rate(distributed_lock_held_seconds_bucket[1m])))`, 대기는 `distributed_lock_wait_seconds_bucket` |
| fail-open | `sum by (lock, reason) (increase(lock_fail_open_total[5m]))`. 로그로는 Loki `{service_name="ResilientPOS"} \|= "잠금 없이 진행한다"` |
| sync 항목 결과·수렴 | `sum by (status) (increase(sync_operation_total[5m]))`, `histogram_quantile(0.95, sum by (le) (rate(sync_convergence_seconds_bucket[5m])))` |
| 발행 기록 | `max(event_publication_incomplete) by (status)` |

PostgreSQL CPU는 앱 metric에 없다. 부하가 안정된 뒤 `docker stats --no-stream`을 한 번 찍어 적는다.
