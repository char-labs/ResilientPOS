# PR spec: k6로 용량 상한을 재고 락·sync 배치·provider 호출의 값과 구조를 정한다

## 목적
용량에 걸린 값이 근거 없이 기본값이거나 상한이 없다(#77). 분산 락 시간(대기 3초, 50ms 스핀, TTL 10초, Redis 명령 타임아웃 1초)은 트래픽 근거가 없어 그대로 뒀고([shared-distributed-lock](2026-09-28-shared-distributed-lock.md) 시간 정책), sync 배치의 동시 실행에는 상한이 없다([sync-request-checkout](2026-09-30-sync-request-checkout.md) 제외 절). provider 승인 호출은 Kafka consumer 스레드에서 동기로 돌아 느린 provider가 파티션을 막고(`PaymentAttemptCreatedListener`), 이벤트 외부화가 도는 비동기 실행기의 큐는 무제한이다.

이 PR은 k6로 대표 부하를 재고, 측정 전에 합의한 판정 규칙으로 값과 구조를 바꾼다. 결과는 원시 수치·환경·재현 명령과 함께 이 spec에 남긴다. #87(단말·매장 inbound rate limit)이 쓸 권장 상한 값도 이 PR의 산출물이다.

기대 결과:
- `load-test/`의 k6 스크립트 다섯 개로 누구나 같은 측정을 다시 돌릴 수 있다.
- 락 대기·보유 시간을 metric으로 본다. fail-open 횟수와 sync 항목 결과·수렴 시간은 #76의 metric으로 본다.
- 락 상수, Redis 타임아웃, Hikari 풀, sync 배치 상한, consumer 설정, 실행기 큐가 실측 근거를 가진 값이 된다.
- provider 호출 구조를 규칙으로 고르고 바꾼다.
- Redisson이 필요해지는 시점이 측정값과 지켜볼 metric 조건으로 wiki에 남는다.

## 범위
소유 모듈은 `platform`(락 시간 metric·실행기·Kafka 설정)과 `payment`(provider 호출·가짜 provider)다. `sync`는 판정 규칙이 배치 크기 상한이나 동시 실행 상한을 바꾸게 할 때만 바뀐다. 부하 스크립트는 저장소 루트 `load-test/`다.

### 트래픽 가정
원문 NFR([시스템 설계 §7](../references/02_System_Design.md#7-nfr-and-observability), [PRD 2.2](../references/01_Product_Planning_PRD.md))을 그대로 쓴다.
- 단말 1,000대, 피크 500 RPS. 주문 p95 200ms 미만, PG를 뺀 결제 처리 p95 150ms 미만, 500 operation 배치 p95 2초 미만.
- 재연결 뒤 60초 안에 단말-서버 주문 상태 99.99% 수렴(G-03).

원문에 없는 값은 가정이다.
- 매장 250곳, 매장당 단말 4대(카페·소형 F&B).
- 동시 재연결: 단말의 10%(100대)가 30분 단절 뒤 500 op backlog를 한꺼번에 보낸다. 60초 안에 수렴하려면 약 833 op/s가 필요하다.
- 부하 구성: 시연 단말(`static/terminal/index.html`)이 판매를 outbox에 쌓고 sync로 보내므로 요청의 80%는 판매 1건짜리 sync 배치(`CREATE_ORDER`와 `REQUEST_CHECKOUT` 2 op), 20%는 HTTP `POST /v1/orders`와 checkout 쌍이다. 80/20은 근거 없는 비율이다. 500 RPS에서 판매(=승인)는 초당 약 450건이다.
- provider 지연: 실제 PG가 없어 [외부 연동 전략 §4.2](../references/04_External_Integration_Strategy.md)의 Toss 출발값(connect 1초, call deadline 3초, bulkhead 20)을 근거로 설계 p99 1초, deadline 3초로 둔다.

### 측정 환경
- 로컬 compose 한 대: `docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build`. 앱 컨테이너 하나(`mem_limit: 1g`), Hikari 기본 풀 10, Kafka 파티션 3.
- k6는 호스트에서 돌린다(v2.2.0). compose에 k6 서비스를 넣지 않는다.
- 서버 지표는 Alloy가 모아 otel-lgtm의 Prometheus·Loki로 보낸 것을 Grafana(`localhost:3000`)에서 읽는다. k6 쪽 수치는 `--summary-export`의 JSON이고 gitignore된 `load-test/results/`에 둔다. 원시 수치는 이 spec의 결과 표에 옮긴다.
- 수치는 운영 절대값이 아니라 같은 머신에서의 상대 비교다. 머신(Apple M3 Pro 11코어, RAM 36GB), OrbStack 자원(CPU 11, 메모리 16GiB), 커밋 SHA, 이미지 digest(`grafana/otel-lgtm:latest`, `grafana/alloy:latest`)를 결과에 남긴다.
- compose 프로젝트 이름이 `resilientpos`로 고정이라 다른 worktree가 띄운 스택과 포트·볼륨을 함께 쓴다. 측정은 그 스택이 비었을 때 하고, 남의 스택을 내리거나 볼륨을 지우기 전에 사용자에게 확인한다.

### k6 스크립트(`load-test/`)
- 공용 setup: 점주 가입 → 로그인 → CSRF 토큰 → 매장 250곳 → 매장마다 단말 4대 등록 → 단말 토큰 1,000개. 점주 세션 쿠키는 `Secure`라 k6가 HTTP에서 자동으로 보내지 않으므로 `Cookie` 헤더로 직접 싣는다. 실행마다 새 이메일로 가입해 데이터가 겹치지 않는다.
- 시나리오는 아래 다섯 개다. 실행 방법은 `load-test/README.md`에 적는다.

| # | 시나리오 | 부하 | 읽는 값 | 정하는 것 |
|---|---|---|---|---|
| S1 | 기준 혼합 | 도착률 50 → 100 → 200 → 300 → 400 → 500 RPS, 단계마다 2분 | uri별 p95·오류율, `hikaricp_connections_pending`·`active`, `kafka_consumer_fetch_manager_records_lag_max`, `executor_queued_tasks`, 힙, 락·Lettuce 지표 | 인스턴스 포화 RPS(p95가 NFR 안이고 오류 0.1% 미만인 가장 높은 단계), Hikari 풀 |
| S2 | 같은 키 주문 생성 경합 | S1 포화의 70%를 바탕에 두고 같은 `(terminalId, clientOrderId)`를 2·5·10·20 VU가 동시에 보낸다 | 락 대기 분포, 보유 시간, SET 호출 수, 409 수, fail-open WARN 줄 수, Lettuce p99 | 락 상수, Redis 타임아웃, Redisson 판정 |
| S3 | sync 배치 동시 실행 | 단말 1·5·10·20·50·100대가 500 op 배치(판매 250건)를 동시에 보낸다. 100대에서는 배치 크기 100·250·500을 비교한다 | 배치 시간 p95, pending, 100대 전체가 확정될 때까지의 시간 | sync 동시 실행 상한, 권장 배치 크기, `MAX_OPERATIONS` |
| S4 | 느린 provider | 판매 초당 50건 고정, 가짜 provider 지연 0·300ms·1초·3초, 각 3분 | `payment.attempt-created` group lag, checkout부터 `PAID`까지의 시간(k6가 결제 시도 조회 API로 확인), 메시지당 provider 밖 처리 시간 | provider 호출 구조, 파티션·concurrency, provider timeout, `max-poll-records` |
| S5 | Kafka 정지 | S1 포화의 70%에서 `kafka`를 60초 멈췄다 다시 띄운다 | 실행기 큐 길이와 증가율, 힙, 요청 지연, `event_publication_incomplete`가 0으로 돌아오는 시간 | 실행기 큐 상한 |

### metric
같은 metric을 두 PR이 따로 만들지 않는다. #76(`feature/observability-alerts-tracing`, spec `docs/spec/2026-10-21-observability-alerts-tracing.md`)이 아래를 만들고 이 측정은 그것을 쓴다.
- `sync_operation_total{status}`: 배치 항목 결과마다 오른다. GAP 수는 `status="GAP"`이다.
- `sync_convergence_seconds`: 단말이 operation을 만든 시각(sync 항목의 선택 필드 `createdAt`)부터 서버가 처음 확정한 시각까지다. k6 스크립트는 항목마다 `createdAt`을 싣는다. 이 앱의 Jackson은 모르는 필드를 거절하지 않으므로 #76 머지 전에도 그대로 받는다.
- `lock_fail_open_total{lock, reason}`: `reason`은 `timeout`과 `error`다. `lock`은 키의 앞 두 마디이고, 그 규칙("모듈:이름:식별자…")은 #76이 `Lock` KDoc에 적는다.

이 PR이 더하는 것은 #76이 계획하지 않은 락 시간 두 개다. Redisson이 필요해지는 시점을 운영에서 지켜보려면 이 둘이 있어야 한다.
- **`distributed_lock_wait_seconds{lock, outcome}`**(Timer): `tryAcquire`의 시작부터 끝까지 걸린 시간이다. `outcome`은 `acquired`(바로든 기다린 뒤든 얻음), `timeout`, `error`다(#76의 `reason`과 같은 값). 대기 빈도는 histogram bucket으로 구한다. 첫 시도에 얻은 요청은 재시도 간격보다 짧은 bucket 안에 든다.
- **`distributed_lock_held_seconds{lock}`**(Timer): 잠금을 얻은 뒤 해제할 때까지다. TTL과 watchdog 판단에 쓴다. 잠그지 못했으면 기록하지 않는다.
- `lock` 태그는 #76이 만드는 키 앞 두 마디 함수를 그대로 쓴다. 태그 규칙을 둘로 만들지 않고, `Lock`의 호출 모양과 네 사용처는 바뀌지 않는다. 호출부가 이름을 넘기는 API는 #76이 기각했고 다시 볼 조건(키를 만드는 호출부가 다섯 곳을 넘거나 규칙 밖 키가 필요해질 때)도 그 spec에 있다.
- 두 Timer는 `management.metrics.distribution.percentiles-histogram`으로 bucket을 내보낸다.
- 순서: 같은 파일(`RedisDistributedLock.kt`)을 #76이 고치므로 이 Timer는 #76이 main에 머지된 뒤 그 위로 rebase해서 구현한다. 그동안 k6 스크립트, 가짜 provider 지연, 실행기 넘침 처리를 먼저 한다. 측정은 #76 머지 뒤 이 브랜치로 한다. #76이 늦어지면 두 브랜치를 로컬에서만 합친 빌드로 재고 그 커밋 SHA를 결과에 남긴 뒤, 머지 뒤 S2를 다시 돌려 같은 수치인지 확인한다. 합친 빌드는 push하지 않는다(스택 PR이 아니다).

### 코드 변경(동작이 바뀌는 것은 TDD)
- **가짜 provider 지연**: `FakePaymentGateway`가 `resilientpos.payment.fake-gateway.latency`(기본 `PT0S`)만큼 `authorize`·`query`에서 잠든다. 기본값 0이라 지금 동작은 그대로다. compose `app` 서비스 환경 변수 한 줄(`RESILIENTPOS_PAYMENT_FAKEGATEWAY_LATENCY`, 호스트의 `FAKE_GATEWAY_LATENCY`로 주고 기본 `PT0S`)로 켠다. Spring의 환경 변수 바인딩은 점을 밑줄로 바꾸고 하이픈을 지운다.
- **실행기 큐 상한**: Modulith의 `EventExternalizerModuleListener.externalize`는 `@ApplicationModuleListener`(`@Async`)라 Boot의 `applicationTaskExecutor`(코어 8, 큐 무제한)에서 돈다. 큐에 상한(`spring.task.execution.pool.queue-capacity`와 `max-size`, 값은 S5)을 두고, 넘친 작업은 `ThreadPoolTaskExecutorCustomizer`가 건 거절 처리기가 버린다. 버릴 때 WARN 한 줄과 counter `task_execution_rejected_total`을 남긴다. Boot 4.1.1에는 거절 정책 설정이 없다. 기본 `AbortPolicy`여도 요청은 성공하고 유실도 없다. `@TransactionalEventListener`의 `AFTER_COMMIT`은 afterCompletion에서 돌고, Spring 7.0.9의 `TransactionSynchronizationUtils.invokeAfterCompletion`이 그 안의 `TaskRejectedException`을 잡아 ERROR 스택 트레이스(`TransactionSynchronization.afterCompletion threw exception`)로 남기기 때문이다. 브로커가 멈추면 거절마다 스택 트레이스가 쌓이므로 WARN 한 줄과 counter로 바꾼다. 버린 이벤트는 발행 기록이 미완료로 남아 `stale-after`(5분) 뒤 재전송 작업이 다시 보낸다.
- **값 변경**: 아래 판정 규칙을 충족하는 값만 바꾼다. 바꾸지 않은 값도 규칙과 수치를 결과에 남긴다.
- **provider 호출 구조**: 아래 규칙으로 고른 안을 이 spec에 범위 조정으로 먼저 적고 구현한다. Kafka consumer 설정을 바꾸면 Spring Kafka·Apache Kafka 공식 문서로 Best Practice를 대조해 근거를 이 spec에 남기고, 기본값은 sources jar나 기동 로그의 ConsumerConfig values로 확인한다. PR 전에 Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트)를 그 문서의 갱신 규칙대로 고친다.

### 판정 규칙(측정 전에 정한다)
| 대상 | 지금 값 | 바꾸는 조건과 새 값 |
|---|---|---|
| 락 재시도 간격 | 50ms | S2에서 기다린 요청의 추가 지연(대기 시간에서 앞 요청의 남은 보유 시간을 뺀 값)의 중앙값이 보유 시간 p50보다 크면 보유 시간 p50으로 줄인다. 하한 10ms |
| 락 대기 한도 | 3초 | 보유 시간 p99.9 × 3 이상인 가장 작은 0.5초 단위 값. 3초를 넘기지 않는다(Hikari `connection-timeout`·행 잠금 대기와 같은 상한) |
| 락 TTL | 10초 | 보유 시간 p99.9 × 10이 TTL보다 크면 그 값으로 올린다 |
| `spring.data.redis.timeout` | 1초 | S1·S2에서 Lettuce 명령 p99.9 × 10이 1초를 넘으면 그 값으로 올린다. 세션·로그인 카운터(fail-closed)도 이 값을 쓰므로 내리지 않는다 |
| Hikari `maximum-pool-size` | 10 | 목표 부하에서 `hikaricp_connections_pending`이 0보다 큰 상태가 1분 넘게 이어지고 PostgreSQL CPU에 여유(70% 미만)가 있으면 pending이 사라지는 가장 작은 값으로 올린다 |
| sync `MAX_OPERATIONS` | 500 | S3에서 500 op 배치 p95가 2초를 넘으면 2초 안에 드는 가장 큰 100 단위 값으로 줄이고 단말 계약(배치 크기 상한)을 wiki에 고친다 |
| sync 동시 실행 상한 | 없음 | S3에서 단말 100대 동시 재연결이 커넥션 대기 초과(Hikari `connection-timeout` 3초)로 배치를 실패시키거나 60초 안에 수렴하지 못하면, pending이 생기기 시작하는 동시 배치 수를 인스턴스별 상한으로 이 PR에서 구현한다. 거절 응답은 그때 범위 조정으로 정하고 #87의 429 규약과 맞춘다. 둘 다 아니면 상한을 두지 않고, 둘 조건과 측정값을 [용량 기준](../wiki/capacity.md)에 남긴다. #87은 같은 단말의 동시 sync 상한을 제외했다(행 잠금이 직렬화하고 rate가 간접 제한한다) |
| 외부화 실행기 크기 | 플랫폼 스레드 코어 8 | 아래 범위 조정(S1 뒤)의 비교 실험으로 고른다. 목표 부하(200·300 RPS)에서 미완료 발행 기록 수가 부하 동안 늘지 않는(마지막 1분 기울기가 0 이하) 가장 작은 조합이 후보다. 플랫폼 스레드 후보와 가상 스레드 후보 중 300 RPS에서 sync p95, 미완료 증가, 힙 최대, PostgreSQL CPU가 더 나은 쪽을 고른다. 둘이 비슷하면(p95 차이 20% 안, 둘 다 미완료가 늘지 않음) 영향 범위가 작은 플랫폼 스레드를 고른다 |
| 실행기 큐 | 무제한 | 외부화 실행기 크기를 정한 뒤 S5의 큐 증가율과 작업당 힙으로 힙 여유(최대 힙의 25%) 안의 가장 큰 값을 상한으로 둔다. 가상 스레드를 고르면 큐가 없으므로 동시 실행 상한(`spring.task.execution.simple.concurrency-limit`)과 거절(`reject-tasks-when-limit-reached`)로 같은 판단을 한다 |
| `max-poll-records` | 50 | 레코드 최대 처리 시간(provider deadline 3초 + provider 밖 처리 p99) × 이 값이 `max.poll.interval.ms`(300초)의 절반을 넘으면 줄인다 |
| provider 호출 구조 | consumer 스레드에서 동기 | 필요 동시 호출 수 = 승인 TPS(450) × 설계 p99(1초)다. S4로 Little의 법칙(처리량 = 동시성 ÷ 지연)과 메시지당 provider 밖 처리 시간을 확인한 뒤, 필요 동시 호출 수가 24 이하면 파티션·concurrency를 그 수로 늘리고 provider timeout(deadline 3초, 넘으면 `UNKNOWN`)을 더한다. 24를 넘으면 `PaymentAttemptCreatedListener`를 비동기 반환(Spring Kafka 4.1.1의 `CompletableFuture` 또는 `suspend`) listener로 바꾸고 in-flight 상한(bulkhead)을 둔다. 지금 가정으로는 450이라 비동기 반환이 될 가능성이 높다 |
| provider timeout | 없음 | deadline 3초(외부 연동 전략 §4.2). 넘으면 결과 불명(`UNKNOWN`)이고 결과 불명 확정 작업이 조회한다 |

### 범위 조정(S1 뒤): 외부화 실행기 크기
S1에서 HTTP 지연은 300 RPS까지 NFR 안이었지만, 이벤트 외부화가 100 RPS부터 입력을 따라가지 못했다(수치는 결과 절).
- 원인: Modulith `EventExternalizerModuleListener.externalize`는 `@ApplicationModuleListener`(`@Async`)라 Boot `applicationTaskExecutor`(코어 8, 큐 무제한)에서 돈다. `CompletableFuture`를 돌려주는 `@Async` 메서드도 실행기 스레드가 그 결과를 기다리므로, 작업 하나가 Kafka ack(producer record queue time 평균 약 8ms, `acks=-1`, `linger.ms=5`)와 발행 기록 완료 표시까지 약 24ms 스레드를 쥔다. 스레드 8개로 초당 약 330~470건이고, 판매 1건이 외부화 이벤트 넷(결제 요청, 시도 생성, 승인, 결제 완료)을 내므로 판매 초당 약 100건이 상한이다.
- 그래서 실행기 큐 상한을 지금 처리량에 두면 평소 부하에서도 이벤트가 버려지고 재전송 주기(5분)만큼 결제가 늦어진다. 큐 상한보다 실행기 크기를 먼저 정한다(판정 규칙 표의 "외부화 실행기 크기").
- 비교 실험(사용자 결정: 둘 다 재고 더 나은 쪽으로 간다). 모두 S1 부하 2분, 앱만 설정을 바꿔 다시 띄운다.
  - 플랫폼 스레드: `spring.task.execution.pool.core-size` 32·64, `spring.datasource.hikari.maximum-pool-size` 10·20. 스레드가 늘면 발행 기록 완료 표시의 커넥션도 늘어 풀과 함께 잰다.
  - 가상 스레드: `spring.threads.virtual.enabled=true`, Hikari 10·20. 이 설정은 `applicationTaskExecutor`뿐 아니라 Tomcat 요청 스레드, 스케줄러, Kafka listener container의 실행기도 가상 스레드로 바꾼다(Boot 4.1.1). 그래서 요청 동시성이 Tomcat 스레드 200개로 묶이지 않고 커넥션 풀에서만 줄 선다.
  - 공통 지표: 미완료 발행 기록 수(`select count(*) from event_publication where completion_date is null`, 15초 간격), 실행기 큐(플랫폼 스레드일 때), uri p95, Hikari pending·timeout, 힙 최대, 앱·PostgreSQL CPU.
- 한계: 운영에서는 pod 수로도 나눌 수 있다. 이 실험은 인스턴스 하나가 감당하는 양을 정한다.

### Redisson 재검토
측정 전에 정한 기각 기준은 셋이다. 셋을 모두 만족하면 Redisson을 들이지 않고 스핀을 유지한다. 하나라도 어기면 그 항목에 맞는 변경(pub/sub 대기 또는 TTL 갱신)을 이 PR에서 한다.
1. S1에서 대기가 획득의 1% 미만이다.
2. S2에서 스핀 SET이 Redis 명령 p99를 10% 넘게 늘리지 않는다.
3. 보유 시간 p99.9가 TTL의 1/10 미만이라 watchdog이 필요 없다.

**Redisson이 필요해지는 시점.** 기각하더라도 다시 볼 조건을 측정값과 함께 [용량 기준](../wiki/capacity.md) wiki에 남긴다. 측정 전 분석은 아래와 같고, 임계값은 결과로 채운다.
- **대기가 흔해질 때(pub/sub 대기).** Redisson `RLock`은 해제를 pub/sub 채널로 알려 기다리는 쪽을 깨운다. 지금 스핀은 기다리는 요청 하나가 초당 SET 20번을 보내고, 대기마다 평균 간격의 절반(약 25ms)을 더 기다리며, 순서를 보장하지 않아 경합이 이어지면 대기 한도까지 밀리는 요청이 생긴다. 지켜볼 조건은 업무 락(`order:create-lock`, `payment:checkout-lock`)의 대기 비율이 장애 밖에서 1% 이상으로 이어지거나 `outcome="timeout"`이 생기는 것이다.
- **블록이 TTL을 넘길 수 있을 때(watchdog).** Redisson은 잡은 인스턴스가 살아 있는 동안 기본 30초(`lockWatchdogTimeout`)짜리 잠금을 늘린다. 지금 락 블록은 외부 호출 없는 짧은 트랜잭션이라 해당하지 않는다. 지켜볼 조건은 `distributed_lock_held_seconds` p99.9가 TTL의 1/10 이상이 되거나, WARN "잠금이 풀기 전에 만료됐다"가 생기거나, provider 호출이나 배치처럼 길이가 들쭉날쭉한 일을 감싸는 새 사용처가 생기는 것이다. Redisson 없이 자체 Lua `PEXPIRE` 갱신으로도 풀 수 있다.
- **락이 정합성의 마지막 방어선이 될 때.** 지금은 fail-open이고 DB 제약이 정본이다. DB가 막지 못하는 외부 자원을 지켜야 하는 사용처가 생기면 fencing token(`RFencedLock`)과 복제 확인(`checkLockSyncedSlaves`)이 필요하다. 다만 fencing은 자원 쪽 검사가 있어야 의미가 있어 Redisson만으로는 끝나지 않는다.
- **다른 분산 도구가 필요할 때.** pod를 가로지르는 동시 실행 상한(`RSemaphore`·`RPermitExpirableSemaphore`)이나 전역 token bucket(`RRateLimiter`)이 필요해지면 그때 Bucket4j(Lettuce)와 Redisson을 비교한다. #87이 인스턴스별이 아니라 전역 상한을 고르면 이 조건에 든다. Redisson을 그쪽에 들이면 락 이전 비용도 작아진다.
- **들일 때 치르는 것.**
  - Lettuce 옆에 클라이언트와 커넥션 풀이 하나 더 생긴다.
  - 기본값(`timeout` 3초, `retryAttempts` 4, `retryDelay` 1~2초 jitter)이면 Redis 장애 때 명령 하나가 실패하기까지 수 초에서 십수 초가 걸린다. 그동안 fail-open이 그만큼 늦어지므로 값을 지금의 1초 기준에 맞춰야 한다.
  - Redisson 예외는 `DataAccessException`이 아니라 fail-open이 잡는 예외 목록을 다시 정해야 한다.
  - 키 형식이 문자열 `SET`에서 hash로 바뀐다. 롤링 배포 중에는 옛 pod와 새 pod가 서로를 막지 못하고 `WRONGTYPE` 오류로 둘 다 fail-open한다. 그동안 DB 제약이 막는다.

### #87 권장 상한
#87(`feature/inbound-rate-limit`, spec `docs/spec/2026-10-21-inbound-rate-limit.md`)은 단말·매장 token bucket을 `resilientpos.rate-limit.{terminal,store}.capacity`와 `refill-per-second`로 받는다. 대상 endpoint 다섯 개가 bucket 하나를 쓰고 sync 요청은 항목 수와 무관하게 1이다. 권장 값도 이 네 값으로 낸다.
- 출발값: 단말 refill은 피크 평균 0.5 RPS(500 RPS ÷ 1,000대)의 4배인 초당 2, 매장 refill은 단말 4대분인 초당 8이다. capacity는 #87 결정 6의 조건으로 정한다. 단말 capacity는 재연결 직후 보낼 sync 요청 수(backlog ÷ 배치 크기)와 그동안의 실시간 요청 수의 합 이상이고, 매장 capacity는 매장 단말 수 × 단말 capacity 이상이다.
- 검증: 오작동 단말 몇 대가 상한 속도로 보내는 동안 정상 단말의 p95가 NFR 안인지, 상한 합계가 인스턴스 포화 RPS의 70% 안인지 k6로 본다. 넘으면 값을 낮추고 이유를 결과에 적는다. #87 limiter가 아직 main에 없으므로 상한 속도로 보내는 부하를 k6가 직접 만들어 흉내 낸다.
- 결과 값과 근거는 이 spec의 결과 절과 [용량 기준](../wiki/capacity.md)에 남긴다. #87 이슈에 위치를 코멘트로 남긴다.

**계약**: HTTP 응답 모양, 이벤트, 오류 code, 스키마는 바뀌지 않는다. 바뀌는 것은 metric 추가, 값 변경, provider 호출 구조, 실행기의 넘침 처리다. sync 배치 크기 상한이 줄면 단말 계약이 바뀌므로 그때 wiki와 결과에 적는다.

**실패와 복구**: 실행기가 버린 이벤트는 발행 기록 재전송이 `stale-after`(5분) 뒤 다시 보낸다. provider timeout은 `UNKNOWN`이고 결과 불명 확정 작업이 조회한다. 락 metric 기록이 실패해도 락 동작은 바뀌지 않는다(Micrometer 기록은 던지지 않는다).

**관측성**: 락 시간 Timer 둘과 실행기 거절 counter. [관측성](../wiki/observability.md)에 이름·태그·쓰는 법을 적는다.

## 제외
- **sync 항목 결과 counter, sync 수렴 시간, 락 fail-open counter.** #76이 만든다. 이 PR은 쓰기만 한다. 수렴 시간은 k6도 단말 쪽에서 잰다(배치의 모든 항목이 확정될 때까지).
- #76의 나머지(알림 규칙, runbook, trace, 재전송 작업 생존 metric). #76의 시작값 임계(LockFailOpen 10분 등)는 그 spec이 "부하테스트 뒤 다시 본다"고 했다. 이 PR은 측정값을 [용량 기준](../wiki/capacity.md)에 남기고 규칙 파일은 고치지 않는다.
- 같은 단말의 동시 sync 상한. #87과 같은 이유로 두지 않는다.
- 앱 여러 대로 재는 것. pod 사이 경합은 인스턴스 하나 안의 스레드 경합으로만 잰다. Redis 왕복 비용은 같아 락 판단에는 충분하다. 앱을 늘리면 compose와 Alloy 설정이 바뀌어 #76과 겹친다.
- Kafka share group(KIP-932) 전환. Kafka 4.2에서 production-ready가 됐지만 브로커를 4.2.1 이상으로 올려야 하고 재시도·DLT가 전달 횟수 한도와 보관 방식으로 바뀌어 지금 DLT 기반 복구 설계를 다시 열어야 한다.
- 실제 PG adapter와 그 HTTP client 설정(connect timeout, 서킷 브레이커). provider timeout은 이 PR이 정한 deadline을 가짜 provider와 호출 구조에 반영하는 데까지다.
- 운영 절대 용량 산정. 로컬 한 대의 상대 비교다.

## 관련 문서
없음

## 변경 경로
- load-test/**
- .gitignore
- docker/docker-compose.yaml
- src/main/kotlin/com/resilientpos/platform/**
- src/main/kotlin/com/resilientpos/payment/internal/application/PaymentAttemptCreatedListener.kt
- src/main/kotlin/com/resilientpos/payment/internal/adapter/gateway/FakePaymentGateway.kt
- src/main/kotlin/com/resilientpos/sync/internal/application/SyncCommandFacade.kt
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/platform/**
- src/test/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/sync/**
- docs/wiki/capacity.md
- docs/wiki/README.md
- docs/wiki/observability.md
- docs/wiki/domain-model.md
- docs/spec/2026-10-21-k6-capacity-measurement.md

## 인수 조건
1. `load-test/`에 공용 setup과 시나리오 S1~S5 스크립트, 실행 방법을 적은 `load-test/README.md`가 있다. README의 명령으로 compose 스택 위에서 각 시나리오가 끝까지 돌고 summary JSON이 `load-test/results/`에 남는다. 이 디렉터리는 git이 추적하지 않는다.
2. `Lock` 블록이 잠금을 얻으면 `distributed_lock_wait_seconds{lock, outcome="acquired"}`와 `distributed_lock_held_seconds{lock}`이 하나씩 기록된다. 대기 한도를 넘기면 `outcome="timeout"`, Redis 오류면 `outcome="error"`가 기록되고 보유 시간은 기록되지 않는다. `lock` 태그는 #76의 `lock_fail_open_total`과 같은 값(키 앞 두 마디)이고 키의 식별자는 태그에 없다. `Lock`의 호출 모양과 키 값은 바뀌지 않는다.
3. k6 sync 배치가 항목마다 `createdAt`을 싣고, 측정 빌드에서 #76의 `sync_operation_total`·`sync_convergence_seconds`·`lock_fail_open_total`이 결과 표의 근거로 쓰인다.
4. `resilientpos.payment.fake-gateway.latency`를 주면 가짜 provider의 `authorize`·`query`가 그만큼 늦게 답하고, 주지 않으면 지금처럼 바로 답한다.
5. 공용 실행기의 큐가 차면 넘친 작업을 던지지 않고 버리고, `task_execution_rejected_total`이 오르고 WARN 한 줄이 남는다. counter는 거절이 없어도 0으로 나간다. 버린 외부화 이벤트의 발행 기록이 미완료로 남았다가 재전송으로 끝나는 것은 S5에서 `event_publication_incomplete`로 본다.
6. 결과 절에 S1~S5의 원시 수치, 환경, 재현 명령이 있다. 판정 규칙 표의 항목마다 바꿨는지와 근거 수치가 있다.
7. provider 호출 구조는 판정 규칙대로 고른 안이 이 spec에 범위 조정으로 먼저 적혀 있고 구현돼 있다. 바꾼 consumer 설정마다 공식 문서 근거와 ConsumerConfig 실제 값이 있다. 느린 provider(S4 같은 지연)에서 다른 주문의 승인이 그 지연에 막혀 줄 서지 않는다는 것을 바꾼 뒤 측정으로 보인다.
8. Redisson 기각 기준 세 개의 판정과 수치가 결과에 있고, [용량 기준](../wiki/capacity.md)에 "Redisson이 필요해지는 시점"이 지켜볼 metric 조건(PromQL)과 측정 임계값으로 있다.
9. #87 권장 상한(단말·매장 RPS, burst, sync 동시 실행 상한)과 검증 수치가 결과와 [용량 기준](../wiki/capacity.md)에 있다.
10. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`이 통과한다.

## 검증
- 개발 중: `python3 scripts/check_pr.py --base origin/main --worktree`. 제출 전: `--worktree` 없이 한 번 더.
- `./gradlew test`(Docker 필요). 좁힐 때는 `./gradlew test --tests 'com.resilientpos.platform.*Lock*'`처럼 실행한다.
- 측정: `load-test/README.md`의 명령. 시나리오마다 Grafana에서 읽은 PromQL을 결과 표에 함께 적는다.
- 정상 시나리오: 기본 설정에서 기존 테스트가 그대로 통과하고 새 metric이 `/actuator/prometheus`에 보인다.
- 실패 시나리오: Redis가 없으면 `outcome="error"`가 오르고 블록은 실행된다. 실행기 큐가 차면 요청은 성공하고 거절 counter가 오른다. provider가 deadline을 넘기면 시도는 `UNKNOWN`이 된다.

## Wiki 영향
- [용량 기준](../wiki/capacity.md)(새 페이지): 트래픽 가정, 인스턴스 포화 RPS, 채택한 값과 근거 spec 링크, #87 권장 상한, Redisson이 필요해지는 시점, 다시 재는 방법. [wiki 인덱스](../wiki/README.md)에 진입점을 더한다.
- [관측성](../wiki/observability.md): 새 metric 셋과 실행기 거절 counter, "아직 없는 것"에서 이 PR이 채운 항목을 걷어낸다.
- [도메인 모델](../wiki/domain-model.md): "분산 락은 `Lock` 블록으로 건다"의 시간 값을 바꾸면 그 값, provider 호출 구조를 바꾸면 그 설명.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트): consumer 설정이나 listener 모양을 바꾸면 갱신 규칙대로 고친다. 볼트 파일이라 저장소 커밋이 없다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트): 바꾼 값과 구조를 볼트에서 고친다.

## 분할 계획
개발 컨텍스트는 "용량 실측과 그 결과로 정하는 값·구조" 하나다. 측정과 그 측정이 정하는 값·구조 변경을 한 PR에 둔다(사용자 결정). sync 항목 결과·수렴 시간·락 fail-open metric과 알림·runbook·trace는 #76, 단말·매장 token bucket은 #87에 둔다.

겹침 확인(origin/main c7810af, spec 첫 커밋 뒤):
- 열린 PR은 draft #94(`refactor/modulith-2-1-2-resubmit`, Modulith 2.1.2 대기)뿐이다. #94의 `build.gradle.kts`, `EventPublicationResubmitter.kt`, 그 통합 테스트는 이 PR이 건드리지 않는다. `docs/wiki/domain-model.md`가 겹칠 수 있고, 늦게 머지되는 쪽이 rebase한다.
- #76(`feature/observability-alerts-tracing`, 아직 PR 없음)은 `RedisDistributedLock.kt`·`Lock.kt`(fail-open counter와 키 규칙), `application.yaml`의 `spring.task.execution`(`propagate-context`), `docs/wiki/observability.md`, `docker/docker-compose.yaml`(Prometheus 규칙 마운트, OTLP endpoint)을 고친다. 락 Timer는 #76 머지 뒤 rebase해서 구현한다. 실행기 설정과 compose 환경 변수는 같은 블록의 다른 줄이라 rebase 때 합친다.
- #87(`feature/inbound-rate-limit`, 아직 PR 없음)은 `terminal` 모듈과 sync web 테스트를 고치고 #77 결과를 기다리는 draft로 열 예정이다. 이 PR이 먼저 머지된다. sync 동시 실행 상한을 구현하게 되면 #87과 같은 거절 응답 규약을 쓰도록 그때 다시 맞춘다.
- #91(`feature/platform-operator-dlt-redrive`)은 spec만 있다. provider 구조 변경이 `KafkaConfiguration.kt`를 고치게 되면 그 시점에 다시 확인한다.
- push·PR 생성·머지 직전에 다시 확인하고 먼저 머지된 쪽에 맞춰 rebase한다.

## TDD 검증 기록
동작이 바뀌는 것(락 시간 metric, 가짜 provider 지연, 실행기 넘침 처리, provider 호출 구조, 판정 규칙이 고르면 sync 동시 실행 상한)에 적용한다. k6 스크립트, 값만 바꾸는 설정, 문서는 적용하지 않는다. 값 변경은 기존 테스트의 회귀 실행으로 확인한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 2, 락 시간 metric: 얻으면 대기·보유 시간을, 대기 초과·Redis 오류면 그 outcome의 대기 시간만 기록한다 | 미실행 | 미실행 | 미실행 |
| 인수 4, `FakePaymentGatewayTest`의 `지연을 주면 승인과 조회가 그만큼 늦게 답한다` | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.gateway.FakePaymentGatewayTest'`. 생성자에 `latency`만 선언하고 쓰지 않은 채 실행. `AssertionFailedError`(승인이 176.875us만에 답했다) | 같은 명령. `authorize`·`query`가 `Thread.sleep(latency)` 뒤 답하게 하고, `query`의 기본 갈래가 `authorize`를 다시 불러 두 번 잠들지 않게 답 고르기를 `answer`로 뺀 뒤 14개 통과. 기본값(`PT0S`)의 기존 13개도 그대로 통과 | `PaymentAuthorizationIntegrationTest`와 함께 실행해 23개 통과(인자 없는 생성자를 쓰는 통합 테스트 픽스처 포함) |
| 인수 5, `RejectedTaskDiscardTest`의 `큐가 차면 넘친 작업을 던지지 않고 버리고 센다` | `./gradlew test --tests 'com.resilientpos.platform.RejectedTaskDiscardTest'`. 아무것도 하지 않는 customizer만 빈으로 선언하고 실행. 셋째 `execute`가 `TaskRejectedException`(ExecutorService in active state did not accept task) | 같은 명령. customizer가 거절 처리기(counter 증가와 WARN)를 걸게 한 뒤 통과 | - |
| 인수 5, `ObservabilityConfigurationTest`의 `prometheus 엔드포인트가 공용 실행기의 거절 수를 거절이 없어도 돌려준다` | characterization. Boot가 `applicationTaskExecutor`에 customizer를 거는지 고정한다. 처음부터 통과했고, `discardRejectedTasks`의 `@Bean`을 뗀 변이로 `AssertionFailedError`(task_execution_rejected_total이 없다)를 확인하고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'` 4개 통과 | - |
| 인수 7, provider 호출 구조(측정 뒤 범위 조정에서 동작을 적는다) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 측정과 개발 뒤 환경, 시나리오별 원시 수치, 판정 규칙별 결론, Redisson 판정, #87 권장 상한, 실행 결과를 추가한다.

### 측정 환경
- 빌드: 로컬 브랜치 `measure/k6-local` 90db602. 이 브랜치 d8c7e05에 #76 브랜치 529a06d를 로컬에서만 합치고(push하지 않음), 아직 #76 머지 전이라 락 대기·보유 Timer를 측정 전용 커밋으로 넣었다. #76의 trace(샘플링 1.0, OTLP로 Alloy), Kafka observation, `propagate-context`가 켜진 상태다.
- 머신: Apple M3 Pro 11코어, RAM 36GB(38654705664 bytes). OrbStack CPU 11, 메모리 16384MiB. Docker 29.4.0. 앱 JDK 25.0.4.1, `mem_limit: 1g`.
- 이미지: `postgres:18-alpine`(d3e1620b530c), `redis:8-alpine`(3811787313eb), `apache/kafka:4.1.0`(bff074a5d005), `grafana/otel-lgtm:latest`(57b5ab1ca661), `grafana/alloy:latest`(9726bfba078d).
- k6 v2.2.0(호스트). 서버 지표는 lgtm 안의 Prometheus(`http://localhost:9090` in container)를 실행의 끝 시각에 `[120s]` 창으로 질의했다. 컨테이너 CPU는 `docker stats` 15초 표본의 평균이다(100% = 1코어).
- 다른 세션의 Gradle 테스트(Testcontainers)가 같은 CPU를 썼다. 각 세션이 알려 준 시각과 표본의 컨테이너 기록으로 겹침을 표시했다. 데이터는 실행마다 새 점주·매장 250곳·단말 1,000대로 넣고 DB를 비우지 않았다.

### S1 기준 혼합(플랫폼 스레드 8, Hikari 10)
재현: `for r in 50 100 200 300 400 500; do k6 run -e RATE=$r --summary-export load-test/results/s1-rate$r.json load-test/s1-baseline.js; done`(사이 45초). 07:17~07:34Z.

| 실행 | 실제 req/s | 실패 | 미처리 iteration | sync p95/p99 | 생성 p95 | checkout p95 | Hikari 대기 max | 획득 max | timeout | 실행기 큐 max | 힙 max | 앱 CPU 평균 | PG CPU 평균 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| s1-rate50 | 57 | 0.00% | 0 | 136/361ms | 93ms | 59ms | 5 | 1.05s | 0 | 63 | 392MB | 79% | 37% |
| s1-rate100 | 106 | 0.00% | 0 | 134/516ms | 96ms | 50ms | 35 | 0.71s | 0 | 1449 | 394MB | 152% | 57% |
| s1-rate200 | 201 | 0.00% | 0 | 55/459ms | 34ms | 26ms | 52 | 1.07s | 0 | 11957 | 526MB | 130% | 69% |
| s1-rate300 | 294 | 0.00% | 0 | 180/418ms | 127ms | 77ms | 99 | 1.07s | 0 | 29106 | 678MB | 194% | 98% |
| s1-rate400 | 236 | 1.17% | 16314 | 4394/12557ms | 4935ms | 3384ms | 194 | 10.12s | 310 | 53249 | 785MB | 376% | 80% |
| s1-rate500 | 24 | 35.90% | 51510 | 46638/50244ms | 42706ms | 35185ms | 183 | 7.34s | 774 | 53240 | 795MB | 562% | 3% |

- 겹침: 50(07:17:12~07:18:45, #91 전체 테스트), 300(끝 23초), 400(07:29:11~07:30:29), 500(전 구간). 100·200은 겹침 없음.
- 요청 지연은 300 RPS까지 NFR 안이다(생성 p95 127ms, 200ms 미만). 400에서 무너졌다(실제 236 req/s, 미처리 16,314, 커넥션 대기 초과 310건이 500 응답).
- 이벤트 외부화가 먼저 막힌다. 실행기 큐가 부하 동안 줄곧 늘었다(15초 표본: 100 RPS 분당 약 1,000건, 200 분당 약 6,000건, 300 분당 약 16,000건). 실행기 스레드 8개가 내내 모두 바빴고 완료는 초당 300~470건이었다. 판매 1건이 외부화 이벤트 넷을 내므로 판매 초당 약 100건이 상한이다. 원인은 범위 조정(S1 뒤) 절에 있다.
- 커넥션 풀은 평균으로는 막히지 않았다. 평균 획득 0.5ms(100)·0.8ms(200)·2.3ms(300), 평균 사용 1.8~2.6ms, 초당 획득 1,514~2,365회. 대기(pending)는 순간적으로 99까지 튀었다. PostgreSQL CPU는 300 RPS에서 평균 98%(11코어 중 약 1코어)다.
- producer: record queue time 평균 7.6ms(200)·9.2ms(300), request latency 약 1ms, 전송 초당 360~391건, 요청당 레코드 약 6.2개.
- 락(100~300 RPS): SET 수가 락 획득 수와 같아 스핀이 없었다(100 RPS SET 11,620, 획득 11,615). 25ms를 넘긴 획득은 0.1~0.4%이고 Redis 응답과 스레드 지연이다. 보유 p50은 2~4ms, p99.9는 `order:create-lock` 0.39~0.50초, `payment:checkout-lock` 0.06~0.10초, 최대 0.55초. fail-open 0건.
- 400 RPS(겹침 있음)에서 `order:create-lock` 보유 p99.9가 10초, 최대 11.61초로 TTL(10초)을 넘겼다. 락 블록(새 트랜잭션) 안에서 커넥션을 기다렸기 때문이다.
- Redis SET p99 1.8~2.9ms, p99.9 7.9~11.2ms(100~300 RPS).
- sync 항목은 모두 `APPLIED`, GAP 0. 서버 수렴 시간 p95는 1초 bucket 안(실시간 판매라 생성 직후 확정)이다.

### 과부하 뒤 회복
- 500 RPS 단계 뒤 07:41:47Z에 미완료 발행 기록이 50,784건이었다. `PUBLISHED` 33,925(실행기 큐에서 기다림), `FAILED` 15,847(07:31:23~07:36:09에 외부화 실패), `RESUBMITTED` 33, `PROCESSING` 8.
- 실행기 큐는 부하가 끝난 뒤에도 잘 줄지 않았다(07:42~07:45Z에 약 50,600에서 47,900). 실행기가 초당 약 125건을 끝내도 완료된 이벤트가 consumer를 거쳐 다음 단계의 이벤트를 다시 넣는다. 판매 1건이 4단계라 순감은 처리량의 일부다.
- `FAILED`는 재전송 작업이 1분에 최대 500건씩 다시 보낸다. 15,847건이면 계산상 약 32분이다.
