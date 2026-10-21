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

## Redisson이 필요해지는 시점
지금 분산 락(`platform.Lock`, `RedisDistributedLock`)은 `SET key token NX PX 10s`로 얻고, 못 얻으면 50ms마다 다시 시도해 3초까지 기다린 뒤 잠금 없이 진행한다(fail-open). 정합성의 정본은 DB 제약이고 락은 같은 요청이 겹칠 때 늦은 쪽이 앞 결과를 받게 하는 완화 수단이다. 블록은 외부 호출 없는 짧은 트랜잭션이다. 이 전제가 유지되는 동안 Redisson(pub/sub 대기, watchdog, fencing, 분산 semaphore·rate limiter)은 필요 없다. 아래 조건 중 하나가 생기면 그 항목에 맞춰 도입을 검토한다.

| 조건 | 지켜볼 신호 | 기준 | 그때 할 일 |
|---|---|---|---|
| 대기가 흔해진다 | 업무 락(`order:create-lock`, `payment:checkout-lock`)에서 기다린 획득의 비율: `1 - sum(rate(distributed_lock_wait_seconds_bucket{outcome="acquired",le="0.025"}[1h])) / sum(rate(distributed_lock_wait_seconds_count{outcome="acquired"}[1h]))` | 장애 밖에서 1% 이상이 이어진다 | 스핀 대신 해제 알림으로 깨운다. Redisson `RLock`이나 Lettuce pub/sub. 스핀은 기다리는 요청 하나가 초당 SET 20번을 보내고, 대기마다 평균 25ms(간격의 절반)를 더 기다리며, 순서를 보장하지 않아 경합이 이어지면 대기 한도까지 밀리는 요청이 생긴다 |
| 대기 한도를 넘긴다 | `sum by (lock) (increase(lock_fail_open_total{reason="timeout", lock=~"order:create-lock\|payment:checkout-lock"}[1h]))` | 장애 밖에서 0보다 크다 | 위와 같다. 주기 작업의 락(`payment:unknown-resolve`, `platform:event-publication`)은 pod가 여럿이면 매분 대기 초과가 정상이라 기준에서 뺀다 |
| 블록이 TTL에 가까워진다 | `histogram_quantile(0.999, sum by (le, lock) (rate(distributed_lock_held_seconds_bucket[1h])))`, WARN `잠금이 풀기 전에 만료됐다` | p99.9가 TTL(10초)의 1/10 이상이거나 WARN이 생긴다 | 잡은 쪽이 살아 있는 동안 TTL을 늘린다. Redisson watchdog(lease를 정하지 않고 잡으면 기본 30초 잠금을 계속 늘린다)이나 자체 Lua `PEXPIRE` 갱신 |
| 길이가 들쭉날쭉한 일을 감싸는 사용처가 생긴다 | 새 `Lock.executeWithLock` 호출 | provider 호출·배치·파일 처리처럼 외부 시간에 묶인 일을 블록에 넣는다 | 위 watchdog 항목과 같다. 그 전에 블록 밖으로 뺄 수 없는지 먼저 본다(지금 규칙은 "락 블록은 외부 호출 없는 짧은 트랜잭션") |
| 락이 마지막 방어선이 된다 | 설계 변경 | DB 제약이 막지 못하는 자원(외부 시스템, 파일)을 락으로만 지킨다 | fencing token(`RFencedLock`)과 복제 동기 확인(`checkLockSyncedSlaves`). fencing은 자원 쪽이 토큰을 검사해야 의미가 있어 Redisson만으로 끝나지 않는다 |
| 다른 분산 도구가 필요해진다 | 설계 변경 | pod를 가로지르는 동시 실행 상한(`RSemaphore`, `RPermitExpirableSemaphore`)이나 전역 rate limiter(`RRateLimiter`) | 그때 Bucket4j(Lettuce)·자체 Lua와 비교한다. 단말·매장 rate limit(#87)은 Redis Lua token bucket을 직접 두기로 해서 지금은 해당하지 않는다 |

**들일 때 치르는 것.**
- Lettuce 옆에 클라이언트와 커넥션 풀이 하나 더 생긴다.
- 기본값이 지금 기준과 다르다(`timeout` 3초, `retryAttempts` 4, `retryDelay` 1~2초 jitter). 그대로 두면 Redis 장애 때 명령 하나가 실패하기까지 수 초에서 십수 초가 걸리고, 그만큼 fail-open이 늦어져 결제와 주문 생성이 멈춘다. 지금 기준(`spring.data.redis.timeout` 1초)에 맞춰 줄여야 한다.
- 예외가 `DataAccessException`이 아니다. fail-open이 잡는 예외 목록(`DataAccessException`, `IllegalStateException`)을 다시 정해야 한다.
- 키 형식이 문자열(`SET`)에서 hash로 바뀐다. 롤링 배포 중에는 옛 pod와 새 pod가 서로를 막지 못하고 `WRONGTYPE` 오류로 둘 다 fail-open한다. 그동안은 DB 제약이 막는다. 키 이름을 바꾸면 오류는 없지만 역시 서로를 막지 못한다.

**도입 이유가 되지 않는 것.**
- Redis 장애 대비. Redisson도 Redis가 없으면 잠그지 못하고, fail-open 여부는 우리 코드가 정한다.
- "많이 쓰는 라이브러리라서." 위 조건이 생기기 전에는 의존성과 설정 대조 비용만 생긴다.
