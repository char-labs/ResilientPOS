# 용량 기준

용량에 걸린 값(분산 락 시간, Redis 타임아웃, 커넥션 풀, sync 배치 상한, consumer 설정, 실행기 큐)을 무엇에 근거해 정했고 언제 다시 보는지 적는다. 측정 방법과 원시 수치는 [k6-capacity-measurement](../spec/2026-10-21-k6-capacity-measurement.md), 실행 명령은 [load-test/README.md](../../load-test/README.md)에 있다. 모든 수치는 로컬 compose 한 대에서 잰 상대 비교다.

## 트래픽 가정
원문 NFR([시스템 설계 §7](../references/02_System_Design.md#7-nfr-and-observability), [PRD 2.2](../references/01_Product_Planning_PRD.md))이 기준이다.

| 항목 | 값 | 근거 |
|---|---|---|
| 단말 | 1,000대 | 원문 |
| 피크 | 500 RPS | 원문 |
| 주문 p95 | 200ms 미만 | 원문 |
| PG 제외 결제 처리 p95 | 150ms 미만 | 원문 |
| 500 operation 배치 p95 | 2초 미만 | 원문 |
| 재연결 뒤 수렴 | 60초 안에 99.99% | 원문 G-03 |
| 매장 | 250곳 × 단말 4대 | 가정(카페·소형 F&B) |
| 동시 재연결 | 단말 10%(100대)가 30분 단절 뒤 500 op씩 | 가정. 60초 수렴에 약 833 op/s |
| 요청 구성 | 80% 판매 1건짜리 sync 배치, 20% HTTP 주문 생성과 checkout 쌍 | 가정. 시연 단말이 판매를 sync로 보낸다. 500 RPS에서 승인은 초당 약 450건 |
| provider 지연 | 설계 p99 1초, deadline 3초 | [외부 연동 전략 §4.2](../references/04_External_Integration_Strategy.md)의 Toss 출발값 |

가정이 바뀌면(매장 규모, 단말이 HTTP 경로를 주로 쓰게 됨, 실제 PG 지연) 이 표와 아래 값을 다시 잰다.

단말 sync 계약은 배치 50 op, 응답 대기 30초이고, 실패하면 같은 배치를 다시 보낸다. `error.errorCode`가 `RATE_LIMITED`이고 `Retry-After`가 1 이상의 정수이면 `Retry-After`초 뒤, 그 밖의 실패(code가 없거나 다른 실패: 네트워크 오류, timeout, 5xx, 401 등)는 5초 뒤에 보내고, 두 경우 모두 0~1초 jitter를 더한다. 그 전에는 보내지 않는다. 시연 단말이 이 계약을 따른다. S3(`BATCH=50 TIMEOUT=30s`)는 배치와 응답 대기를 따르고, 실패하면 종류와 관계없이 jitter 없이 5초 뒤 다시 보낸다. 서버의 `Retry-After`(지금 값으로 1초)보다 늦게 보내 하한은 지키지만 jitter는 없다. 근거는 [slo-alert-followups](../spec/2026-10-23-slo-alert-followups.md) 결정 7과 [terminal-demo-retry-after](../spec/2026-10-26-terminal-demo-retry-after.md)다.

## 인스턴스 하나의 용량
2026-10-21~22에 로컬 compose 한 대(Apple M3 Pro, OrbStack CPU 11·메모리 16GiB, 앱 `mem_limit: 1g`, trace 샘플링 1.0)에서 쟀다. 22일 측정은 호스트 부하(load average 30~51)와 VM 정지로 흔들려, 판정에는 흔들리지 않은 실행만 썼다. 원시 수치와 흔들린 구간은 spec 결과 절에 있다.

| 항목 | 측정 | 시나리오 |
|---|---|---|
| HTTP | 약 300 RPS에서 주문 생성 p95 127ms. 400 RPS에서 무너졌다(p95 4.4초, 커넥션 대기 초과 310건). 실행기 큐 상한 전 빌드라 400 RPS에서는 큐 53,249건·힙 785MB로 GC 압박도 함께 있었다 | S1 |
| 이벤트 외부화 | 판매 초당 약 100건(외부화 초당 330~470건)이 상한이다. 넘으면 실행기 큐가 부하 동안 계속 쌓인다(300 RPS 2분에 29,106건) | S1 |
| 외부화 병목 | PostgreSQL 커밋의 WAL fsync(`LWLock:WALWrite`·`IO:WalSync`). 외부화 한 건이 발행 기록을 두 번 커밋한다. 실행기 스레드(32·64), Hikari 20, 가상 스레드로 풀리지 않았다 | 실행기 비교 |
| 승인 consumer | 처리량 = 파티션 수 ÷ provider 지연. 지연 1초면 인스턴스 하나가 초당 3건이다. provider 밖 처리는 평균 4.3ms | S4 |
| sync 동시 재연결 | 단말 100대 × 500 op가 39.9초에 수렴했고 실패는 0이다. 커넥션 대기는 동시 50대부터 생기고(최대 97) 획득 최대 2.86초로 한도 3초에 가깝다. 단독 500 op 배치는 1.5초 | S3 |
| sync 동시 재연결(단말 계약) | 단말 100대 × 500 op가 최대 22.1초에 수렴했고 실패·timeout은 0이다. 배치 p95 3.9초·최대 4.7초, 커넥션 대기 최대 93, 획득 최대 0.81초, 대기 초과 0이다. 100대 × 1,800 op는 최대 70.5초에 수렴했다(배치 최대 4.7초, 실패 0). 처리량은 초당 약 2,300~2,600 op다 | S3 변형(2026-10-23, 배치 50·timeout 30초, #117 동시 상한 위) |
| 브로커 정지 | 70 RPS에서 Kafka 65초 정지에 실행기 큐 8,242건. 요청 지연은 거의 그대로다(외부화가 커밋 뒤 비동기) | S5 |
| 과부하 뒤 회복 | 미완료 발행 기록 50,784건이 약 19분에 0이 됐다. 재전송 주기를 5초로 줄이면 36,653건이 약 12분에 0이 됐다 | S1 뒤, 가상 스레드 실험 뒤 |
| 발행 기록 표 | 측정 이틀에 `event_publication`이 약 200만 행·1.5GB가 됐고 부하 중 autovacuum이 되풀이됐다. 완료 모드 `update`와 7일 보관이라 운영 피크(판매 초당 수백 건 × 이벤트 넷)면 7일에 수억 행이 된다 | 2차 측정 |

인스턴스 하나의 용량을 넘는 부하는 pod 수로 나눈다. 외부화 상한은 DB 커밋에 묶여 있어 pod를 늘려도 같은 DB의 커밋 처리량을 넘지 못한다. 운영 디스크의 커밋 지연, 완료 모드와 보관 기간, 외부화 한 건의 커밋 수가 바뀌면 다시 잰다.

## 채택한 값
| 값 | 지금 | 근거 | 다시 볼 조건 |
|---|---|---|---|
| 락 대기 한도 | 1.5초 | 견딜 수 있는 부하에서 보유 p99.9 최대 0.5초 × 3을 0.5초 단위로 올림 | 보유 p99.9가 0.5초를 넘을 때 |
| 락 재시도 간격 | 10ms에서 두 배씩 100ms까지(지터), 해제 알림이 오면 바로 | 첫 간격은 보유 p50(1~7ms)에 맞춰 짧은 대기를 빨리 끝낸다. 상한은 같은 키 20개 동시에서 10ms 고정 간격이 SET p99를 5배 넘게 올린 것을 막는다 | 대기가 흔해질 때(아래 Redisson 조건) |
| 락 TTL | 10초 | 보유 p99.9 × 10이 5초 이하 | 같음 |
| `spring.data.redis.timeout` | 1초 | Redis SET p99.9(흔들리지 않은 실행 8~11ms, 흔들린 실행 최대 53ms)의 10배보다 길다. 세션·로그인 카운터(fail-closed)도 이 값을 쓴다 | 명령 p99.9가 100ms를 넘을 때 |
| Hikari 풀 | 10 | 20으로 늘려도 외부화 처리량이 늘지 않았고 지연만 나빠졌다 | 커밋 지연이 다른 환경 |
| sync `MAX_OPERATIONS` | 500 | 원문 NFR(500 op 배치 p95 2초 미만) 안인 것은 단독 배치(1.5초)뿐이다. 동시 재연결 5대부터 2.07초로 넘고, 100대에서는 배치를 100 op로 줄여도 9.4초라 배치 크기로는 들지 않는다 | 단독 배치가 2초를 넘을 때. 단말은 50 op씩 보내고, 100대 동시 재연결에서 배치 최대 4.7초로 단말 timeout 30초 안이다 |
| sync 동시 실행 상한 | 두지 않는다 | 100대 동시 재연결이 커넥션 대기 초과 없이 60초 안에 수렴. 단말 계약(배치 50, timeout 30초)으로는 22.1초다 | 동시 재연결에서 대기 초과가 나거나 60초를 넘길 때, 배치 시간이 단말 timeout(30초)에 가까워질 때 |
| 공용 실행기 | 플랫폼 스레드 8, 큐 12,000, 넘치면 버림 | 스레드를 늘려도 처리량이 늘지 않음. 작업당 약 16KB × 12,000이 최대 힙(805MB)의 25% 안 | 외부화 한 건의 커밋 수가 줄 때, 앱 메모리 한도나 trace 샘플링이 바뀔 때 |
| `max-poll-records` | 40 | 승인 consumer의 레코드 최대 3.7초(deadline 3초 + 처리 최대 0.7초) × 40 = 148초가 `max.poll.interval.ms`의 절반 아래. 0.7초는 정상 부하의 최대이고, 커넥션 대기·행 잠금(각 3초)이 겹치는 최악은 이 계산 밖이다 | deadline이나 처리 시간이 바뀔 때 |
| provider deadline | 3초 | 외부 연동 전략 §4.2 출발값 | 실제 PG |
| Kafka 파티션·승인 consumer | 3, 동기 호출 | 필요 동시 호출 수(100 이상)를 파티션으로 맞출 수 없다. 비동기 반환 listener는 실패한 offset을 재시도·DLT보다 먼저 커밋해 보류했다([도메인 모델](domain-model.md)) | 오래 `REQUESTED`인 시도의 회수 경로가 생길 때 |

## 단말·매장 rate limit 권장값
단말·매장 token bucket(#87, PR #102)의 권장값이다. #102가 이 값과 아래 상한 조건의 근거를 그 spec 결정 8에 반영했다. 측정 빌드에는 limiter가 없어 429와 거절 지속 시간은 k6 요청 패턴에 token bucket을 적용해 계산했다.

| 설정 | 값 | 근거 |
|---|---|---|
| `resilientpos.rate-limit.terminal.capacity` | 10 | 30분 내내 피크(판매 초당 0.5건 × op 2)로 팔다 끊긴 단말의 backlog 1,800 op는 배치 500으로 요청 4개다. 그동안의 실시간 판매는 같은 outbox의 다음 배치에 실린다. 트래픽 가정의 500 op는 원문 NFR의 배치 크기로 잡은 보통 재연결이고, 이 값은 최악이다. 단말 sync 계약(배치 50)으로는 500 op가 요청 10개라 capacity 안이지만 1,800 op는 36개다. 서버가 한가해 배치를 연달아 보내면 10개 남짓 뒤 429를 받는다. 시연 단말은 1~2초 뒤 그동안 찬 만큼(2~4개)씩 이어 보내 남은 26개에 약 13초, S3는 5초 뒤 bucket이 다시 찬 채로 이어 보낸다(계산, 미측정) |
| `resilientpos.rate-limit.terminal.refill-per-second` | 2 | 단말 피크 평균 0.5 RPS(판매 1건 = sync 1요청 또는 HTTP 2요청이라 약 0.55 RPS)의 약 4배 |
| `resilientpos.rate-limit.store.capacity` | 40 | 매장 단말 4대 × 단말 capacity |
| `resilientpos.rate-limit.store.refill-per-second` | 8 | 매장 단말 4대 × 단말 refill. 고장 단말 k=1~3이어도 같은 매장 정상 단말 몫이 남는다(필요 3.5·5.0·6.5/s) |

- 상한 조건은 빈도 상한만으로는 성립하지 않는다. 고장 단말이 capacity만큼 한꺼번에 보내면 요청마다 커넥션을 쥔다(최악 행 잠금 대기 3초). 단말별 동시 요청 상한(#101)이 이 빈틈을 막는다. 한 단말의 쓰기 요청이 pod 하나의 풀에서 handler 단계에 쥐는 커넥션은 최대 2개(sync 1개와 그 밖 1개, 풀 10의 20%)이고, 거절된 요청도 인증 조회는 한 번 치른다. 측정(2026-10-23, 앱 한 대, 바탕 70 RPS, [terminal-concurrency-limit](../spec/2026-10-23-terminal-concurrency-limit.md) 결과 절): 고장 단말 하나가 같은 500 op 배치를 5초마다 10개 동시에 보내면 바꾸기 전에는 다른 단말 sync p95가 29ms에서 166ms, pending 최대가 0에서 23이 됐다. 바꾼 뒤에는 묶음마다 1개만 통과해 p95 39~47ms, pending 최대 7~10이었다. 호스트 부하와 222만 행의 발행 기록 위에서 잰 값이라 10% 해상도의 판정은 아니다.
- 시나리오별 계산: Retry-After를 무시하고 초당 20번 보내는 단말은 89.6%가 429이고, 초마다 10개 동시면 79.2%다. 한 매장 4대 동시 재연결은 단말 bucket 1/10, 매장 bucket 4/40만 써서 429가 없다.
- 매장 refill 합계(250 × 8 = 2,000/s)는 인스턴스 용량보다 훨씬 크다. 이 상한은 매장 사이 공정성 장치이고 전역 용량 보호가 아니다.

## HTTP 지연 기준선
지연 알림(`HttpLatencyAboveTarget`)의 임계는 원문 NFR(생성 200ms, checkout 150ms, sync 500 op 배치 2초)이고 아래 측정값은 그 임계가 견딘 단계와 무너진 단계를 가르는지 보는 판별 근거다([slo-alert-baseline](../spec/2026-10-23-slo-alert-baseline.md)). 무너진 400 RPS에서는 세 경로 모두 p95가 3~5초로 임계를 넘었다. 로컬 한 대는 외부화 상한(약 100 RPS) 아래에서 sync p95 134ms·생성 96ms·checkout 50ms(100 RPS), HTTP 상한 근처 300 RPS에서 sync 180ms·생성 127ms·checkout 77ms였다. 운영 기준선은 운영 환경의 첫 주 분포로 다시 잡는다.

## Redisson이 필요해지는 시점
지금 분산 락(`platform.Lock`, `RedisDistributedLock`)은 `SET key token NX PX 10s`로 얻고, 못 얻으면 해제 알림(채널 `platform:lock-released`)이나 재시도 간격(10ms에서 100ms까지 늘어나는 지터) 중 먼저 오는 것에 다시 시도해 1.5초까지 기다린 뒤 잠금 없이 진행한다(fail-open). 정합성의 정본은 DB 제약이고 락은 같은 요청이 겹칠 때 늦은 쪽이 앞 결과를 받게 하는 완화 수단이다. 업무 락(`order:create-lock`, `payment:checkout-lock`)의 블록은 외부 호출 없는 짧은 트랜잭션이다. `payment:unknown-resolve`는 블록 안에서 provider 조회를 최대 100건 하고 조회는 읽기라 겹쳐도 해가 없어, 아래 조건에서 뺀다. 해제 알림은 Spring Data Redis 구독으로 직접 두었다. 이 전제가 유지되는 동안 Redisson(watchdog, fencing, 공정 대기, 분산 semaphore·rate limiter)은 필요 없다. 아래 조건 중 하나가 생기면 그 항목에 맞춰 도입을 검토한다.

| 조건 | 지켜볼 신호 | 기준 | 그때 할 일 |
|---|---|---|---|
| 대기가 흔해진다 | 업무 락(`order:create-lock`, `payment:checkout-lock`)에서 기다린 획득의 비율: `sum(rate(distributed_lock_wait_seconds_count{outcome="waited", lock=~"order:create-lock\|payment:checkout-lock"}[1h])) / sum(rate(distributed_lock_wait_seconds_count{outcome=~"acquired\|waited", lock=~"order:create-lock\|payment:checkout-lock"}[1h]))` | 장애 밖에서 1% 이상이 이어진다 | 순서를 보장하는 대기(Redisson `RFairLock`이나 자체 대기열)나 키별 채널을 본다. 지금 해제 알림은 pod마다 대기열의 맨 앞 하나를 깨운다(모두 깨우면 해제마다 SET이 몰렸다). 다시 시도한 요청은 줄 뒤에 다시 서고 pod 사이에도 순서가 없어 경합이 이어지면 대기 한도까지 밀리는 요청이 생기고, 모든 해제가 모든 pod에 방송된다 |
| 대기 한도를 넘긴다 | `sum by (lock) (increase(lock_fail_open_total{reason="timeout", lock=~"order:create-lock\|payment:checkout-lock"}[1h]))` | 장애 밖에서 0보다 크다 | 위와 같다. 주기 작업의 락(`payment:unknown-resolve`, `platform:event-publication`)은 pod가 여럿이면 매분 대기 초과가 정상이라 기준에서 뺀다 |
| 블록이 TTL에 가까워진다 | `histogram_quantile(0.999, sum by (le, lock) (rate(distributed_lock_held_seconds_bucket{lock=~"order:create-lock\|payment:checkout-lock"}[1h])))`, WARN `잠금이 풀기 전에 만료됐다`. 보유 bucket이 250ms 다음 500ms라 그 사이의 p99.9는 보간값이고 `_max`와 함께 본다 | p99.9가 TTL(10초)의 1/10 이상이거나 WARN이 생긴다 | 잡은 쪽이 살아 있는 동안 TTL을 늘린다. Redisson watchdog(lease를 정하지 않고 잡으면 기본 30초 잠금을 계속 늘린다)이나 자체 Lua `PEXPIRE` 갱신 |
| 길이가 들쭉날쭉한 일을 감싸는 사용처가 생긴다 | 새 `Lock.executeWithLock` 호출 | provider 호출·배치·파일 처리처럼 외부 시간에 묶인 일을 블록에 넣는다 | 위 watchdog 항목과 같다. 그 전에 블록 밖으로 뺄 수 없는지 먼저 본다(지금 규칙은 "락 블록은 외부 호출 없는 짧은 트랜잭션") |
| 락이 마지막 방어선이 된다 | 설계 변경 | DB 제약이 막지 못하는 자원(외부 시스템, 파일)을 락으로만 지킨다 | fencing token(`RFencedLock`)과 복제 동기 확인(`checkLockSyncedSlaves`). fencing은 자원 쪽이 토큰을 검사해야 의미가 있어 Redisson만으로 끝나지 않는다 |
| 다른 분산 도구가 필요해진다 | 설계 변경 | pod를 가로지르는 동시 실행 상한(`RSemaphore`, `RPermitExpirableSemaphore`)이나 전역 rate limiter(`RRateLimiter`) | 그때 Bucket4j(Lettuce)·자체 Lua와 비교한다. 단말·매장 rate limit(#87)은 Redis Lua token bucket을 직접 두기로 해서 지금은 해당하지 않는다 |

**들일 때 치르는 것.**
- Lettuce 옆에 클라이언트와 커넥션 풀이 하나 더 생긴다.
- 기본값이 지금 기준과 다르다(`timeout` 3초, `retryAttempts` 4, `retryDelay` 1~2초 jitter). 그대로 두면 Redis 장애 때 명령 하나가 실패하기까지 수 초에서 십수 초가 걸리고, 그만큼 fail-open이 늦어져 결제와 주문 생성이 멈춘다. 지금 기준(`spring.data.redis.timeout` 1초)에 맞춰 줄여야 한다.
- 예외가 `DataAccessException`이 아니다. fail-open이 잡는 예외 목록(`DataAccessException`, `IllegalStateException`)을 다시 정해야 한다.
- 키 형식이 문자열(`SET`)에서 hash로 바뀐다. 롤링 배포 중에는 옛 pod와 새 pod가 서로를 막지 못하고 `WRONGTYPE` 오류로 둘 다 fail-open한다. 그동안은 DB 제약이 막는다. 키 이름을 바꾸면 오류는 없지만 역시 서로를 막지 못한다.

**측정 판정(2026-10-22).** Redisson은 들이지 않는다. 기각 기준 1·3은 충족했고 기준 2는 어겨서, 규칙대로 해제 알림(pub/sub) 대기를 Spring Data Redis로 직접 넣었다. Redisson의 나머지(watchdog, fencing)는 필요한 사용처가 없다.
1. 기준 부하에서 실제 대기가 없었다. SET 수와 락 획득 시도 수가 같았다(100 RPS 12,102 대 12,103, 300 RPS 22,976 대 22,973).
2. 같은 키 20개 동시(S2)에서 SET p99가 대기 방식과 무관하게 바탕의 10배 넘게 올랐다. 10ms 고정 간격은 18.8ms, 알림이 모두 깨우면 38.0ms, 하나만 깨우면 22.3ms였고 바탕은 1.0~2.5ms였다. SET 수는 하나만 깨울 때 요청당 5.1번으로 가장 적다(10ms 고정 간격 8.2번, 모두 깨움 11.3번). p99는 SET 수와 함께 움직이지 않아 경합 요청 자체와 호스트 부하 영향으로 본다. 20 동시에서 대기 초과 fail-open은 2.8%로 10ms 고정 간격(0.3%)보다 많았고 DB unique가 409로 막았다. 같은 실행의 바탕 지연이 더 나빠 호스트 부하가 섞였고, 순서를 지키는 대기열에서 다시 잴 일이다. 2 동시에서 기다린 획득의 대기 p50은 7~10ms다.
3. 견딜 수 있는 부하의 보유 p99.9는 최대 0.5초로 TTL의 1/10(1초) 아래다. 과부하(400 RPS)에서는 락 블록 안의 커넥션 대기로 보유가 11초까지 늘었는데, 이것은 watchdog이 아니라 용량 문제다.

**도입 이유가 되지 않는 것.**
- Redis 장애 대비. Redisson도 Redis가 없으면 잠그지 못하고, fail-open 여부는 우리 코드가 정한다.
- "많이 쓰는 라이브러리라서." 위 조건이 생기기 전에는 의존성과 설정 대조 비용만 생긴다.
