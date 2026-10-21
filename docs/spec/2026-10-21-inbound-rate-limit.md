# PR spec: 단말·매장 단위로 inbound 요청 빈도에 상한을 둔다

## 목적
#81로 `/v1/**` 요청마다 인증된 단말과 그 매장을 알게 됐지만, 한 단말이나 매장이 보낼 수 있는 요청의 **빈도**에는 상한이 없다. 오작동하는 단말 하나가 sync 배치나 checkout을 몰아 보내면 다른 매장과 나눠 쓰는 DB 커넥션과 행 잠금이 고갈된다. 같은 단말의 sync는 watermark 행 잠금을 기다리는 동안(최대 3초) 커넥션을 쥐고 있어서, 겹쳐 보낸 배치 수만큼 커넥션이 묶인다. [request-resource-limits](2026-09-21-request-resource-limits.md)는 요청 **하나**의 상한만 두고 빈도를 제외했고, [terminal-authentication](2026-10-05-terminal-authentication.md)은 이 작업을 "인증 주체가 생긴 뒤의 별도 컨텍스트"로 미뤘다(이슈 #87). 근거는 외부 연동 전략 §5.1(`docs/references/04_External_Integration_Strategy.md`)의 inbound token bucket이다: "terminal credential/store 단위 token bucket을 적용한다. 정상 offline backlog가 단말을 영구 차단하지 않게 retry-after와 recovery window를 제공한다."

기대 결과:
- 인증된 `/v1/**` 요청은 단말 bucket과 매장 bucket을 모두 통과해야 handler에 닿는다. 넘으면 429 `RATE_LIMITED`와 `Retry-After`를 받는다.
- 오프라인에서 돌아온 단말의 정상 backlog는 영구히 막히지 않는다.
- 거절은 counter와 INFO 로그 한 줄로 남는다.

## 범위
소유 모듈은 `terminal`이다. 인증 인터셉터가 limiter를 부르고, Redis limiter는 `terminal.internal.adapter.redis`에 둔다(owner의 `RedisLoginFailureCounter` 선례). `platform`은 429 예외, `ErrorType`, advice 처리를 맡는다(`UnauthenticatedException` 선례). 빈도 상한은 업무 사실이 아니라 시한이 있는 보호 장치라 도메인 port를 두지 않는다. 설정은 `application.yaml`이다.

**결정 1: 단말 bucket과 매장 bucket을 둘 다 두고 한 번에 확인한다.**
- 문제: 한 단말이나 한 매장이 공유 자원을 다 쓸 수 있다.
- 제약: 점주는 매장에 단말을 개수 제한 없이 등록할 수 있다. POS에서는 한 단말의 고장이 같은 매장의 다른 단말 결제를 막으면 영업이 멈춘다.
- 수단: Lua 스크립트 한 번으로 두 bucket을 확인하고, 둘 다 토큰이 1개 이상 있을 때만 둘 다 1씩 차감한다. 한쪽이 거절하면 어느 쪽도 차감하지 않는다.
- 키는 `rate-limit:{storeId}:terminal:terminalId`와 `rate-limit:{storeId}:store`다. 중괄호는 Redis Cluster hash tag다. 두 키가 다른 slot이면 스크립트가 CROSSSLOT 오류를 내고, fail-open(결정 4)이 그 오류를 삼켜 보호가 조용히 꺼진다. hash tag로 두 키를 같은 slot에 둔다.
- 버린 것: 단말만 두면 매장 총량이 "등록 단말 수 × 상한"이라 사실상 상한이 없다. 매장만 두면 고장 단말 하나가 그 매장 정상 단말의 checkout까지 429로 막는다.

**결정 2: 대상은 인증 직후의 `/v1/**` 전체이고, 요청 하나를 1로 센다. bucket은 단말·매장마다 하나다.**
- 대상 endpoint: `POST /v1/orders`, `POST /v1/orders/{orderId}/checkout`, `GET /v1/orders/{orderId}/payment-attempts`, `POST /v1/payment-attempts/{paymentId}/retry`, `POST /v1/terminals/{terminalId}/sync`.
- sync 요청도 1이다. 요청 하나의 비용은 이미 항목 500개, 본문 1 MiB, 트랜잭션 30초로 묶여 있다. 같은 단말의 sync는 watermark 행 잠금으로 항목 단위로 직렬화된다. 정상 sync는 backlog가 수천 건이어도 요청 수가 몇 개라 같은 bucket을 쓰는 checkout을 굶기지 않는다.
- 위치: `TerminalAuthenticationInterceptor`가 `authenticate` 직후 limiter를 한 줄로 부른다. "인증 뒤에 제한한다"는 순서가 호출부에서 바로 보인다. 별도 인터셉터는 순서가 `addInterceptors`의 등록 순서에 기대서 두 파일을 함께 봐야 보이므로 버렸다.
- 인증에 실패한 요청은 단말을 모르므로 세지 않는다(제외).
- 버린 것: sync만 별도 bucket을 두면 bucket이 넷, 값이 네 쌍이 되고 경로로 분류하는 코드가 생긴다. sync를 항목 수만큼 차감하면 본문을 읽은 뒤에야 셀 수 있어 인터셉터와 Controller 두 곳에서 세야 하고, 재연결 backlog 배출이 같은 bucket을 쓰는 실시간 checkout을 429로 막는다.
- 치르는 것: 고장 단말의 sync 반복은 "refill × 최대 500항목"까지 허용된다. 같은 단말의 동시 sync 수는 직접 막지 않고 rate가 간접적으로만 제한한다(제외).

**결정 3: Redis Lua token bucket이다.**
- bucket마다 남은 토큰과 마지막 갱신 시각을 Redis hash에 둔다. 요청이 오면 지난 시간 × 초당 refill만큼 채우되 capacity를 넘지 않는다. 시간은 스크립트 안에서 Redis `TIME`(초와 마이크로초)으로 읽어 pod 사이의 시계 차이와 무관하다.
- 알고리즘은 Spring Cloud Gateway `RedisRateLimiter`의 `request_rate_limiter.lua`(Apache-2.0)를 따르고, 두 키를 원자적으로 다루도록, 시각을 마이크로초 단위로 쓰도록 고쳤다.
- 키 TTL은 빈 bucket이 가득 차는 시간(capacity ÷ refill)이다. 그 뒤에 키가 없으면 가득 찬 bucket과 같다.
- `StringRedisTemplate`으로 부른다. 기존 잠금·로그인 실패 카운터와 같은 커넥션, 같은 timeout 1초, 같은 예외 변환(`DataAccessException`)을 쓴다. 새 의존성은 없다.
- 버린 것:
  - Bucket4j 8.21.0(`bucket4j_jdk17-lettuce`): 요청마다 왕복이 2회(GET 뒤 CAS)이고, 두 bucket을 원자적으로 함께 차감하지 못해 매장에서 거절되면 단말 토큰을 돌려주는 보정이 필요하다. Spring이 아닌 native Lettuce 클라이언트를 따로 연결해야 해서 예외 타입과 timeout을 별도로 다뤄야 하고, 의존성이 하나 는다.
  - pod 메모리: 실제 상한이 "pod 수 × 설정값"이라 scale할 때마다 조용히 바뀌고 매장 총량을 묶는 목적이 흐려진다. 재기동하면 초기화된다.
  - Resilience4j RateLimiter: pod 메모리 안에서만 동작하고 키(단말)별 제한이 아니다.
  - Spring Cloud Gateway `RedisRateLimiter` 자체: WebFlux gateway 전용이다.
- 치르는 것: Lua 약 25줄을 이 저장소가 소유하고 테스트한다. `/v1` 요청마다 Redis 왕복이 하나 는다.

**결정 4: Redis 장애는 fail-open이다.**
- `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)이면 제한 없이 통과시키고 WARN 한 줄(예외 포함)을 남긴다. 그 밖의 예외는 프로그래밍 오류라 삼키지 않는다. `RedisDistributedLock`과 같은 기준이다.
- 근거: `/v1`은 Redis 장애에 막히지 않는다는 현재 설계를 지킨다. Redis health를 끈 것, `publish-request-handled-events: false`, 잠금의 fail-open이 모두 이 원칙에서 나왔다. 로그인 실패 카운터가 fail-closed인 것은 비밀번호 대입을 막는 장치이고 점주 경로라서다.
- 치르는 것: 장애 중에는 보호가 없다. Redis가 느리면 `/v1` 요청마다 최대 1초가 더해진다(circuit breaker는 제외). 장애 중에는 WARN이 `/v1` 요청마다 한 줄씩 쌓인다. 잠금도 이미 같은 수준으로 남긴다.
- 설정 값이 capacity 1 미만이거나 refill 0 이하이면 기동하지 못한다. 0이면 스크립트가 나눗셈에서 오류를 내고 fail-open이 그 오류를 삼켜 보호가 조용히 꺼지기 때문이다.

**결정 5: 거절 응답은 429, `Retry-After`, `RATE_LIMITED` 하나다.**
- 상태는 429다. `Retry-After`는 거절한 bucket이 토큰 1개를 다시 얻기까지의 시간을 초 단위로 올림한 정수이고 최소 1이다(RFC 9110 §10.2.3의 delta-seconds). 두 bucket이 모두 거절하면 긴 쪽을 쓴다.
- 본문은 기존 envelope이고 `error.errorCode`는 `RATE_LIMITED`, 문구는 고정이다. 단말 bucket이 거절했는지 매장 bucket이 거절했는지는 응답에서 구분하지 않는다. 받은 단말이 할 일(기다렸다가 다시 보내기)이 같기 때문이다. 어느 쪽인지는 로그와 metric에만 남긴다.
- `ErrorType.RATE_LIMITED`로 둔다. 업무 규칙 위반이 아니라 HTTP 공통 오류라서 `DomainException`이 아니다(`UNAUTHENTICATED` 선례).
- 예외는 `platform.RateLimitedException`이고 대기 초와 로그용 메시지(scope·terminalId·storeId)를 싣는다. 고장 단말이 반복해도 요청마다 스택 한 덩어리가 쌓이지 않게 스택 트레이스를 만들지 않는다. 토큰과 `Authorization` 값은 싣지 않는다.
- 버린 것: code를 `TERMINAL_RATE_LIMITED`·`STORE_RATE_LIMITED` 둘로 나누는 안(새 code가 둘이고 단말이 할 일은 같다), `TOO_MANY_REQUESTS`라는 이름(참고 문서가 쓰는 rate-limited와 달라 outbound의 429와 나란히 둘 때 덜 분명하다).

**결정 6: 단말 재시도 규약과 backlog 보장은 token bucket 자체로 한다.**
- 429는 handler 전에 판정하므로 서버 상태가 바뀌지 않는다. 단말은 endpoint가 멱등인지와 관계없이 같은 요청을 `Retry-After`초에 0~1초 jitter를 더한 뒤 그대로 다시 보낸다. jitter는 매장 전체가 함께 끊겼다 돌아온 단말들이 같은 초에 몰리지 않게 하려는 것이다. §5.1의 "payment의 rate limit은 단순 429로 끝나면 안 된다"는 처리 도중의 거절을 말한다. inbound 429는 결제 상태를 건드리기 전이라 재조회 없이 다시 보내면 된다.
- sync는 기존 규약대로 응답의 `lastAckedSequence` 다음부터 이어 보낸다.
- 거절된 요청은 토큰을 쓰지 않는다.
- backlog: 단말이 쉬는 동안 bucket이 capacity까지 차므로, 재연결 직후에는 capacity만큼 바로 통과한다(§5.1의 recovery window). 그 뒤로는 refill 속도로 빠져나간다. refill이 0보다 크고 거절이 상태를 바꾸지 않으므로, 정상 트래픽이 refill을 넘지 않는 한 영구 차단은 없다.
- 값이 지켜야 할 조건(결정 8이 #77 결과로 확인한다):
  - 단말 capacity ≥ 재연결 직후 보낼 sync 요청 수 + 그동안의 실시간 요청 수
  - 단말 refill ≥ 단말 하나의 정상 최대 요청 빈도
  - 매장 capacity ≥ 매장 단말 수 × 단말 capacity(매장 전체가 함께 끊겼다 돌아오는 경우)
  - 매장 refill ≥ 매장 하나의 정상 최대 요청 빈도
- 규약은 REST Docs의 429 스니펫과 [아키텍처](../wiki/architecture.md)의 요청 흐름에 적는다.
- 버린 것: 재연결 단말에 따로 완화 창을 주는 안(마지막 접속 시각을 따로 추적해야 하고 capacity가 이미 하는 일을 두 번 한다), sync를 제한에서 빼는 안(이 이슈가 막으려는 "sync 배치를 몰아 보내는 고장 단말"이 보호 밖에 남는다).

**결정 7: 관측은 counter 하나와 거절 INFO 로그다.**
- counter `inbound.rate.limited{scope}`(Prometheus 이름 `inbound_rate_limited_total`), `scope`는 `terminal`이나 `store`다. `terminalId`·`storeId`는 label로 쓰지 않는다([관측성](../wiki/observability.md)).
- 거절마다 advice가 INFO 한 줄(`method`·`uri`·`errorCode`와 예외 메시지의 scope·terminalId·storeId)을 남긴다. 스택은 없다. backlog를 비우다 받는 429는 정상 동작이라 WARN 경보를 채우지 않게 INFO로 둔다. 고장 감지는 counter의 증가율로 한다.
- Redis 오류는 결정 4의 WARN이다.
- 버린 것: 로그만 남기는 안(참고 문서 §7.1의 필수 신호 `rate_limited_total`이 비고, #77 측정과 경보가 로그를 세야 한다), fail-open 횟수 counter를 더하고 거절을 WARN으로 남기는 안(metric이 하나 더 늘고 정상적인 backlog 배출도 WARN을 쌓는다).

**결정 8: 상한 값은 설정이고, #77 실측 값으로 채운 뒤 ready로 바꾼다.**
- `resilientpos.rate-limit.terminal.capacity`, `resilientpos.rate-limit.terminal.refill-per-second`, `resilientpos.rate-limit.store.capacity`, `resilientpos.rate-limit.store.refill-per-second`다. 환경 변수로 덮을 수 있다.
- 개발 중에는 자리값(단말 capacity 100·초당 5, 매장 capacity 500·초당 25)을 둔다. 근거 없는 값이다.
- PR은 draft로 연다. #77(`perf/k6-capacity-measurement`)의 권장 값으로 바꾸고 그 근거를 이 spec에 적은 뒤, 검증을 다시 돌리고 ready로 바꾼다.
- 테스트는 판별할 수 있는 작은 값을 테스트 안에서 준다.

**계약.** 새 HTTP 오류 code `RATE_LIMITED`(429) 하나와 응답 헤더 `Retry-After`가 생긴다. 기존 응답 모양, 이벤트, 토픽, DB 스키마는 바뀌지 않는다.

**복구.** 거절은 상태를 바꾸지 않으므로 복구할 것이 없다. 단말은 `Retry-After` 뒤에 같은 요청을 다시 보낸다.

**범위 조정(2026-10-21, 구현 중).** `@WebMvcTest`는 경로와 관계없이 모든 `HandlerInterceptor` 빈을 싣는다. 그래서 웹 슬라이스 테스트 12개가 모두 `TerminalAuthenticationInterceptor`를 만들고, 지금 `TerminalManagement`를 mock으로 받는다. 인터셉터가 limiter에 의존하면 12개 모두 limiter도 같은 방식으로 mock으로 받아야 한다. 점주 경로의 슬라이스 테스트(owner·store·reconciliation)를 변경 경로에 더한다. 동작은 바뀌지 않는다.

## 제외
- 인증 전 요청의 제한. 잘못된 토큰을 반복하는 요청은 단말을 모르므로 세지 않고, 요청마다 인증 조회 한 번을 그대로 치른다. 이런 요청은 edge(API gateway·ingress)가 IP 단위로 막는다(§5.1).
- 점주 경로(`/owner/**`)와 가입·로그인의 IP별 rate limit. 현재 상태가 외부 노출 조건으로 따로 적은 항목이다.
- 같은 단말의 동시 sync 수 상한(§5.1의 concurrent sync). 같은 단말의 sync는 행 잠금으로 직렬화되고 동시 요청 수는 rate가 간접 제한한다.
- endpoint별 bucket과 sync 항목 수 가중. 결정 2.
- Redis 지연에 대한 circuit breaker. 결정 4.
- Redis 장애(fail-open) 횟수 metric. 결정 7.
- outbound(provider) rate limit과 bulkhead.
- 시연 화면(`static/terminal`)의 `Retry-After` 처리. 지금도 전송에 실패하면 5초 뒤 다시 보낸다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/web/TerminalAuthenticationInterceptor.kt
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/redis/**
- src/main/kotlin/com/resilientpos/platform/ErrorType.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/RateLimitedException.kt
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/terminal/**
- src/test/kotlin/com/resilientpos/platform/**
- src/test/kotlin/com/resilientpos/order/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/payment/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/sync/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/owner/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/store/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/reconciliation/internal/adapter/web/**
- docs/wiki/architecture.md
- docs/wiki/observability.md

## 인수 조건
1. 단말 bucket에 토큰이 없으면 인증된 `/v1` 요청은 429 `RATE_LIMITED`와 `Retry-After`를 받고 Controller에 닿지 않는다.
2. 매장 bucket이 비면 같은 매장의 다른 단말도 429다. 다른 매장의 단말은 영향을 받지 않는다.
3. 한쪽 bucket이 거절하면 다른 쪽 bucket의 토큰은 줄지 않는다.
4. 거절된 요청은 토큰을 쓰지 않는다. `Retry-After`만큼 기다리면 같은 요청이 통과한다.
5. capacity만큼은 연달아 통과하고 그다음 요청이 거절된다. 쉬면 refill 속도로 다시 차고 capacity를 넘지 않는다.
6. `Retry-After`는 토큰 1개를 다시 얻기까지의 시간을 초로 올림한 값이고 최소 1이다.
7. Redis에 닿지 못하면 요청은 통과하고 WARN이 남는다.
8. 인증에 실패한 요청은 limiter를 부르지 않고 401이다.
9. 거절마다 `inbound_rate_limited_total{scope}`가 1 오르고, INFO 로그 한 줄에 scope·terminalId·storeId가 남는다. 토큰과 `Authorization` 값은 남지 않는다.
10. capacity가 1 미만이거나 refill이 0 이하이면 기동하지 못한다.
11. 429 응답을 REST Docs 스니펫으로 문서화한다.
12. 상한 값이 #77의 권장 값이고 그 근거가 이 spec에 있다. ready로 바꾸기 전 조건이다.
13. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test --tests 'com.resilientpos.terminal.*'` — 인터셉터와 Lua 스크립트. 스크립트는 Testcontainers Redis로 실제 실행한다.
- `./gradlew test` — 전체 통과. `/v1` 슬라이스 테스트가 limiter를 받아도 깨지지 않는다.
- 실패 시나리오: 닿을 수 없는 Redis로 fail-open, 0 refill 설정으로 기동 실패, 매장 거절 때 단말 토큰 보존.
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), 제출 전에 `--worktree` 없이 한 번 더.
- PR 전 `resilientpos-multi-review`. 신뢰 경계의 입력 제한이라 보안 관점과 `spring-backend-specialist`를 넣는다.

## Wiki 영향
- [아키텍처](../wiki/architecture.md) — 요청 흐름 1번(인증) 뒤에 빈도 상한 단계와 429 재시도 규약을 넣는다.
- [관측성](../wiki/observability.md) — counter `inbound_rate_limited_total{scope}`, 거절 INFO 로그, Redis 오류 WARN을 적는다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트) — "속도 제한(rate limit)도 없다"를 이 PR의 상태로 바꾸고 Redis 쓰임에 단말 rate limit을 더한다. 볼트 파일이라 이 PR diff에는 없다.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트) — 무영향. Kafka·Modulith 작업이 아니다.
- 요구사항 변화: 인증된 단말 요청에 단말·매장 단위 빈도 상한이 생기고, 넘으면 429와 `Retry-After`다. 단말은 `Retry-After` 뒤 같은 요청을 다시 보낸다.

## 분할 계획
- 이 PR은 inbound 빈도 상한 하나의 개발 컨텍스트다. 상한 값의 실측은 #77(`perf/k6-capacity-measurement`)의 몫이고, 이 PR은 draft로 그 결과를 기다린다(결정 8).
- 겹침 확인(2026-10-21, origin/main `c7810af`): 열린 PR은 #94(draft, `refactor/modulith-2-1-2-resubmit`)뿐이고 그 경로(`build.gradle.kts`, `EventPublicationResubmitter`와 그 테스트, `domain-model.md`, 자기 spec)는 이 PR과 겹치지 않는다. 진행 중이지만 아직 push되지 않은 브랜치 중 `feature/platform-operator-dlt-redrive`(#91)는 `ApiControllerAdvice`·`ErrorType`·보안 설정을, `feature/observability-alerts-tracing`(#76)은 `observability.md`와 metric 규칙을 고칠 수 있다. push·PR 생성·머지 직전에 다시 확인하고 먼저 머지된 쪽에 맞춰 rebase한다.
- 공용 헬퍼: main과 #94에 rate limiter나 429 처리가 없다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| capacity만큼 통과하고 다음 요청을 거절한다 | 미실행 | 미실행 | 미실행 |
| 쉬면 refill 속도로 다시 차고 capacity를 넘지 않는다 | 미실행 | 미실행 | 미실행 |
| 매장 bucket이 거절하면 단말 토큰을 차감하지 않는다 | 미실행 | 미실행 | 미실행 |
| 거절은 토큰을 쓰지 않고 대기 시간을 돌려준다 | 미실행 | 미실행 | 미실행 |
| Redis 오류면 통과시키고 WARN을 남긴다 | 미실행 | 미실행 | 미실행 |
| 설정 값이 범위 밖이면 기동하지 못한다 | 미실행 | 미실행 | 미실행 |
| 인증 뒤 limiter가 거절하면 429와 Retry-After이고 Controller에 닿지 않는다 | 미실행 | 미실행 | 미실행 |
| 인증 실패는 limiter를 부르지 않는다 | 미실행 | 미실행 | 미실행 |
| 거절이 counter와 INFO 로그로 남는다 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성.
