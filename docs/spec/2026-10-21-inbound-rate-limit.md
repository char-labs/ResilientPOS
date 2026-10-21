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
- 치르는 것: 고장 단말의 sync 반복은 "refill × 최대 500항목"까지 허용된다. capacity는 순간 허용량이라 같은 단말의 **동시** 요청을 막지 못한다. bucket이 가득 찬 고장 단말이 capacity만큼을 한꺼번에 보내면 요청마다 행 잠금을 기다리며 DB 커넥션을 쥔다. 결정 6의 상한 조건으로 값을 묶고, 하한과 충돌하면 #101(단말별 동시 요청 상한)로 푼다.
- 치르는 것: 거절된 요청도 인증 조회 1회, Redis 왕복 1회, INFO 로그 1줄을 치른다. limiter는 handler 뒤의 비용(트랜잭션·행 잠금)만 막는다. `Retry-After`를 무시하고 폭주하는 단말은 edge가 `Authorization` 해시나 IP 단위로 막는다(제외).

**결정 3: Redis Lua token bucket이다.**
- bucket마다 남은 토큰과 마지막 갱신 시각을 Redis hash에 둔다. 요청이 오면 지난 시간 × 초당 refill만큼 채우되 capacity를 넘지 않는다. 시간은 스크립트 안에서 Redis `TIME`(초와 마이크로초)으로 읽어 pod 사이의 시계 차이와 무관하다.
- 알고리즘은 Spring Cloud Gateway `RedisRateLimiter`의 `request_rate_limiter.lua`(Apache-2.0)를 따랐다. 코드는 가져오지 않고 두 키를 원자적으로 다루고 시각을 마이크로초로 쓰도록 새로 썼다.
- 키 TTL은 빈 bucket이 가득 차는 시간(capacity ÷ refill)이다. 그 뒤에 키가 없으면 가득 찬 bucket과 같다.
- 저장된 시각이 Redis의 지금보다 미래면(failover로 새 primary의 시계가 뒤처진 경우) 그 시각을 지금으로 고쳐 쓴다. 거절이어도 쓴다. 그러지 않으면 시계가 따라잡을 때까지 refill이 멈추고, 거절은 아무것도 쓰지 않으므로 그동안 짧은 `Retry-After`로 거절이 되풀이된다.
- `StringRedisTemplate`으로 부른다. 기존 잠금·로그인 실패 카운터와 같은 커넥션, 같은 timeout 1초, 같은 예외 변환(`DataAccessException`)을 쓴다. 새 의존성은 없다.
- 버린 것:
  - Bucket4j 8.21.0(`bucket4j_jdk17-lettuce`): 요청마다 왕복이 2회(GET 뒤 CAS)이고, 두 bucket을 원자적으로 함께 차감하지 못해 매장에서 거절되면 단말 토큰을 돌려주는 보정이 필요하다. Spring이 아닌 native Lettuce 클라이언트를 따로 연결해야 해서 예외 타입과 timeout을 별도로 다뤄야 하고, 의존성이 하나 는다.
  - pod 메모리: 실제 상한이 "pod 수 × 설정값"이라 scale할 때마다 조용히 바뀌고 매장 총량을 묶는 목적이 흐려진다. 재기동하면 초기화된다.
  - Resilience4j RateLimiter: pod 메모리 안에서만 동작하고 키(단말)별 제한이 아니다.
  - Spring Cloud Gateway `RedisRateLimiter` 자체: WebFlux gateway 전용이다.
- 치르는 것: Lua 약 25줄을 이 저장소가 소유하고 테스트한다. `/v1` 요청마다 Redis 왕복이 하나 는다.

**결정 4: Redis 장애는 fail-open이다.**
- `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)이면 제한 없이 통과시키고 스택 없는 한 줄을 남긴다(아래 수준·간격). 장애 중에는 실패가 요청마다 나므로 스택을 붙이면 로그가 장애를 키운다. 그 밖의 예외는 프로그래밍 오류라 삼키지 않는다. 잡는 범위는 `RedisDistributedLock`과 같다.
- 로그 수준은 근본 원인으로 가른다. 근본 원인이 Redis의 오류 응답(Lettuce `RedisCommandExecutionException`)이고 그 코드가 기다리면 풀리는 것(LOADING·BUSY·TRYAGAIN·CLUSTERDOWN·MASTERDOWN)이 아니면 ERROR다. 스크립트 오류, OOM, NOPERM, CROSSSLOT, READONLY, WRONGTYPE, 연결 단계의 WRONGPASS·NOAUTH가 여기에 든다. 사람이 설정이나 스크립트를 고치기 전까지 모든 요청에서 보호가 꺼져 있기 때문이다. 그 밖(연결 실패, timeout, 끊긴 동안의 거절, 기다리면 풀리는 오류 응답)은 WARN이다. 통과(fail-open)는 같다. 줄에는 감싼 예외가 아니라 근본 원인의 클래스와 메시지를 남긴다.
- 같은 수준의 줄은 10초에 한 줄만 남기고 그 사이 제한 없이 통과한 건수와 직전 줄의 시각을 다음 줄에 함께 적는다(`suppressedSinceLast`, `lastLoggedAt`). 시각이 있어야 장애 사이를 넘어 이월된 건수를 가를 수 있다. 시계가 뒤로 가면 바로 남긴다. `REJECT_COMMANDS`로 실패가 바로 나므로 요청마다 남기면 로그 양이 요청 수(부하·공격)와 같아져 수집기와 디스크를 누른다(재리뷰 Codex·Claude 지적, 2026-10-21 사용자 결정으로 "요청마다 한 줄"을 바꿨다).
- 기동할 때 검사용 키(`rate-limit:{preflight}:…`, 운영 키와 같은 함수로 만든다)로 스크립트를 한 번 돌리고 결과도 운영과 같은 함수로 읽는다. 앱 쪽 설정 오류면 기동을 멈춘다. 설정 오류는 Redis가 NOPERM·NOAUTH·WRONGPASS·CROSSSLOT·WRONGTYPE이나 스크립트 오류(`ERR user_script`, `ERR Error running script`), 막힌 명령(`ERR unknown command`)으로 답한 경우만이다. 잘못된 ACL·스크립트·slot이 모든 pod의 보호를 끄는 대신 rollout에서 멈추게 하려는 것이다. 그 밖(Redis에 닿지 못함, OOM, READONLY, 연결 수 초과처럼 Redis 쪽 상태)은 WARN만 남기고 뜬다. Redis 사고 중에도 다시 뜨는 pod와 앱 핫픽스가 막히지 않게 하려는 것이다(3차 리뷰 Claude H1·specialist P2. 처음 구현은 런타임 ERROR 기준을 그대로 써서 이 경우에도 기동을 막았다). 대가로 Redis ACL·스크립트를 고칠 때까지 앱을 배포할 수 없다(2026-10-21 사용자 결정). 기동 시점의 `IllegalStateException`은 잡지 않는다. 기동에는 닫힌 커넥션 팩토리가 없으므로 그 예외는 수명 주기 문제이고, 삼키면 검사가 조용히 꺼진다.
- 연결 계열 실패(연결 실패, timeout, 끊긴 동안의 거절, Lettuce queue 초과, 기다리면 풀리는 오류 응답) 뒤 1초 동안은 Redis를 부르지 않고 바로 통과시킨다. 한 번도 연결되지 못한 pod는 Spring 공유 연결의 잠금 안에서 요청마다 connect timeout(1초)을 기다려 사실상 멈추고, Redis가 느리거나 단절되면 요청마다 1초가 더해지기 때문이다. 이렇게 하면 Redis 호출은 pod당 1초에 한 번으로 줄고 나머지는 기다리지 않는다. Redis가 오류로 답한 설정 오류는 건너뛰지 않는다(기다려도 풀리지 않고, 호출이 빠르다). 대가로 Redis가 돌아온 뒤 최대 1초 동안 보호가 없다(3차 리뷰 Claude M1, 2026-10-21 사용자 결정으로 "circuit breaker 제외"를 이 1초 창만큼 바꿨다). 건너뛴 요청도 다음 WARN 줄의 건수에 들어간다.
- 잡는 범위를 연결 실패·timeout으로 좁히지 않는다. Redis의 OOM·READONLY·NOPERM도 `RedisSystemException`(`DataAccessException`)으로 오므로, 좁히면 이런 인프라 장애에서 모든 `/v1`이 500이 되어 이 결정과 어긋난다. 대가로 스크립트 결함과 CROSSSLOT도 통과로 삼켜진다. 스크립트는 실제 Redis 통합 테스트가, CROSSSLOT은 두 키가 같은 slot인지 고정하는 테스트가 막고, 그래도 운영에서 나면 위의 ERROR가 드러낸다(multi-review의 Codex 지적을 좁히기 대신 ERROR로 받은 이유). `IllegalStateException`도 메시지로 가르지 않는다. 잡는 `try` 블록에는 `redis.execute` 호출 하나뿐이고 이 경로에서 그 예외는 닫힌 커넥션 팩토리만 던진다. 이 저장소 코드의 프로그래밍 오류는 블록 밖이라 삼켜지지 않는다. 잠금·로그인 카운터와 같은 기준이다.
- Lettuce는 기본값(`DisconnectedBehavior.DEFAULT`, `requestQueueSize` 무제한)에서 연결이 끊긴 동안 명령을 버퍼에 받아 둔다. 그러면 Redis가 재시작하는 동안 `/v1` 요청마다 timeout 1초를 다 기다리고, 끝난 명령도 재연결 전까지 버퍼에 남아 장애가 길면 힙이 불어난다(lettuce-core 7.5.2 `DefaultEndpoint` 소스로 확인). `platform`에 `LettuceClientOptionsBuilderCustomizer`를 두어 `REJECT_COMMANDS`로 바꾼다. Boot가 넣은 connect timeout과 `TimeoutOptions` 뒤에 적용되므로 그 설정은 남는다. 끊긴 것을 아는 동안 명령이 바로 실패해 빈도 상한과 잠금은 즉시 fail-open, 점주 세션과 로그인은 즉시 503이 된다. 지금 정책과 방향이 같다. 대가로 유휴 연결이 닫히거나 Redis가 연결을 끊은 뒤 재연결하는 몇 ms 사이에도 점주 세션과 로그인은 바로 503이고, 처리 중이던 명령도 다시 보내지 않는다.
- 같은 customizer에서 `requestQueueSize`를 1000으로 묶는다. 기본값(무제한)이면 패킷이 사라지는 단절(RST 없음)에서 응답 없는 명령이 연결이 끊길 때까지(커널 재전송 한도, Linux 기본 약 15분) 쌓이고 그동안 모든 `/v1` 요청이 timeout 1초를 기다린다. 묶으면 응답 없는 명령이 1000개 쌓인 뒤부터 새 명령이 바로 거절돼 즉시 fail-open이 되고 힙도 그만큼만 쓴다(lettuce-core 7.5.2 `CommandHandler`의 "Internal stack size exceeded"로 확인). 1000은 요청 스레드(Tomcat 기본 200)와 주기 작업이 한꺼번에 내는 명령보다 넉넉히 크다. 스레드 수를 바꾸는 변경(virtual thread 포함)은 이 값을 다시 본다. queue가 차면 Lettuce의 `DefaultEndpoint`가 거절마다 스택이 붙은 WARN(`Unexpected exception during request`)을 자기 로거로 남긴다(7.5.2 `RetryListener`). 그 로거는 이 WARN 한 종류뿐이고 실패는 호출자(빈도 상한·세션·잠금)가 이미 남기므로 `application.yaml`에서 그 로거를 ERROR로 올린다(3차 리뷰 specialist P1). TCP keepalive는 Lettuce 기본값으로 켜져 있어(13초 쉬면 13초 간격 3회, 확장 keepalive를 지원하는 JDK 11+ NIO에서) 밤사이 유휴 연결이 조용히 끊기는 경우는 1분 안에 드러난다. 바쁜 연결의 단절을 몇 초 안에 잡는 `TCP_USER_TIMEOUT`은 epoll·io_uring 네이티브 transport가 있어야 해서 넣지 않는다(새 의존성).
- 근거: `/v1`은 Redis 장애에 막히지 않는다는 현재 설계를 지킨다. Redis health를 끈 것, `publish-request-handled-events: false`, 잠금의 fail-open이 모두 이 원칙에서 나왔다. 로그인 실패 카운터가 fail-closed인 것은 비밀번호 대입을 막는 장치이고 점주 경로라서다.
- 치르는 것: 장애 중에는 보호가 없다. 연결 계열 실패 뒤 1초 생략창 덕에 Redis가 느리거나 단절되거나 한 번도 연결되지 못한 pod에서도 Redis를 기다리는 요청은 pod당 1초에 한 번이다. 다만 `REJECT_COMMANDS`의 부작용으로 잠금(`RedisDistributedLock`)의 Redis 오류 WARN(스택 포함)은 1초 대기 없이 잠금 호출마다 남는다. 잠금은 이 PR 밖이라 후속에서 같은 방식(근본 원인 한 줄, 간격)으로 바꾼다.
- 설정 오류 메시지에는 어느 bucket의 어떤 값인지 적는다.
- 설정 값이 capacity 1 미만이거나 refill이 0 이하·무한대·NaN이면 기동하지 못한다. 그런 값이면 스크립트가 계산에서 오류를 내고 fail-open이 그 오류를 삼켜 보호가 조용히 꺼지기 때문이다. 빈 bucket이 가득 차는 시간(capacity ÷ refill, 곧 키 TTL)이 하루를 넘거나 capacity가 2^53(Lua의 double이 정수를 정확히 다루는 상한)을 넘어도 기동하지 못한다. 앞은 단위 실수(초당을 하루당으로 적음)이고 뒤는 토큰 차감이 정밀도에 묻혀 제한이 사라지는 값이다.

**결정 5: 거절 응답은 429, `Retry-After`, `RATE_LIMITED` 하나다.**
- 상태는 429다. `Retry-After`는 거절한 bucket이 토큰 1개를 다시 얻기까지의 시간을 초 단위로 올림한 정수이고 최소 1이다(RFC 9110 §10.2.3의 delta-seconds). 두 bucket이 모두 거절하면 긴 쪽을 쓴다.
- 본문은 기존 envelope이고 `error.errorCode`는 `RATE_LIMITED`, 문구는 고정이다. 단말 bucket이 거절했는지 매장 bucket이 거절했는지는 응답에서 구분하지 않는다. 받은 단말이 할 일(기다렸다가 다시 보내기)이 같기 때문이다. 어느 쪽인지는 로그와 metric에만 남긴다.
- `ErrorType.RATE_LIMITED`로 둔다. 업무 규칙 위반이 아니라 HTTP 공통 오류라서 `DomainException`이 아니다(`UNAUTHENTICATED` 선례).
- 예외는 `platform.RateLimitedException`이고 대기 초(1 이상만 받는다)와 로그용 메시지(scope·terminalId·storeId)를 싣는다. 고장 단말이 반복해도 요청마다 스택 한 덩어리가 쌓이지 않게 스택 트레이스를 만들지 않는다. 토큰과 `Authorization` 값은 싣지 않는다.
- 429·`RATE_LIMITED`는 handler 전의 거절에만 쓴다. 단말의 재전송 규약(결정 6)은 HTTP 상태가 아니라 `error.errorCode == RATE_LIMITED`와 `Retry-After`에 묶는다. 나중에 handler 안에서 상태가 바뀐 뒤의 거절(outbound limiter, provider 429)이 429로 나가더라도, 단말이 그것을 같은 요청의 재전송으로 다루지 않게 하려는 것이다. 구조 테스트 둘로 고정한다. `RateLimitedException`은 `RedisTerminalRateLimiter`만 만들고(생성자 시그니처가 바뀌어도 규칙이 비지 않게 소유 타입으로 맞춘다), `acquire`는 `TerminalAuthenticationInterceptor`만 부른다. 본문이 없는 edge(ingress)의 429도 앱 전에 나므로 같은 요청을 다시 보내도 안전하다.
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
- 상한 조건(고장 단말이 공유 자원을 묶지 않게): 단말 capacity와 "단말 refill × 요청 하나가 커넥션을 쥐는 최대 시간(잠금 대기 3초 포함)"이 한 단말이 한꺼번에 쥐어도 되는 커넥션 수를 넘지 않는다. 매장도 같은 식으로 한 매장이 쥐어도 되는 커넥션 수를 넘지 않는다. 하한과 상한이 함께 성립하지 않으면 #101(단말별 동시 요청 상한)이 필요하다는 근거다.
- 격리 조건(결정 1이 막으려는 것): 매장 refill ≥ 단말 refill × k + 나머지 단말의 정상 빈도 합. k는 한 매장에서 동시에 고장 났다고 보는 단말 수다. capacity도 같은 형태다. 이것이 깨지면 상한 안에서 움직이는 고장 단말 몇 대가 매장 bucket을 비워 같은 매장 정상 단말의 checkout이 429가 된다.
- #77 k6에 넣을 시나리오: (1) 유효 토큰 하나로 `Retry-After`를 무시하고 초당 N개를 보낸다. (2) 한 매장의 단말 M대가 함께 재연결해 backlog를 보낸다. (3) bucket이 가득 찬 단말 하나가 sync를 동시에 보낸다. 셋 모두 `hikaricp_connections_pending`, 다른 매장의 p95, 429 비율을 본다.
- 규약은 REST Docs의 429 스니펫과 [아키텍처](../wiki/architecture.md)의 요청 흐름에 적는다.
- 버린 것: 재연결 단말에 따로 완화 창을 주는 안(마지막 접속 시각을 따로 추적해야 하고 capacity가 이미 하는 일을 두 번 한다), sync를 제한에서 빼는 안(이 이슈가 막으려는 "sync 배치를 몰아 보내는 고장 단말"이 보호 밖에 남는다).

**결정 7: 관측은 counter 하나와 거절 INFO 로그다.**
- counter `inbound.rate.limited{scope}`(Prometheus 이름 `inbound_rate_limited_total`), `scope`는 `terminal`이나 `store`다. `terminalId`·`storeId`는 label로 쓰지 않는다([관측성](../wiki/observability.md)).
- 거절 로그는 limiter가 scope별로 10초에 한 줄 INFO(`빈도 상한으로 거절했다 scope=… terminalId=… storeId=… retryAfterSeconds=… suppressedSinceLast=… lastLoggedAt=…`)로 남기고 advice는 429를 로그로 남기지 않는다. `Retry-After`를 무시하는 단말이 초당 수천 번 보내도 로그 양이 그만큼 늘지 않게 하려는 것이다(3차 리뷰 Codex 3, 2026-10-21 사용자 결정으로 "거절마다 한 줄"을 바꿨다). counter는 거절마다 오른다. 한 단말이 폭주하면 거의 모든 줄이 그 단말이라 여전히 드러난다. 여러 단말이 함께 거절될 때(매장 전체 재연결)는 표본 한 단말만 보인다. backlog를 비우다 받는 429는 정상 동작이라 WARN이 아니라 INFO다. 고장 감지는 counter의 증가율로 한다.
- 원인 단말은 `scope=terminal` 줄의 terminalId다. `scope=store` 줄의 terminalId는 매장 bucket이 빈 순간 막힌 단말(피해 단말)이다. 원인 단말이 자기 상한 안에서 매장 bucket을 비웠다면 그 단말의 거절 줄은 없다.
- Redis 오류는 결정 4의 WARN·ERROR(10초에 한 줄)다.
- 버린 것: 로그만 남기는 안(참고 문서 §7.1의 필수 신호 `rate_limited_total`이 비고, #77 측정과 경보가 로그를 세야 한다), fail-open 횟수 counter를 더하고 거절을 WARN으로 남기는 안(metric이 하나 더 늘고 정상적인 backlog 배출도 WARN을 쌓는다).

**결정 8: 상한 값은 설정이고, #77 실측 값으로 채운 뒤 ready로 바꾼다.**
- `resilientpos.rate-limit.terminal.capacity`, `resilientpos.rate-limit.terminal.refill-per-second`, `resilientpos.rate-limit.store.capacity`, `resilientpos.rate-limit.store.refill-per-second`다. 환경 변수로 덮을 수 있다.
- 개발 중에는 자리값(단말 capacity 100·초당 5, 매장 capacity 500·초당 25)을 둔다. 근거 없는 값이다.
- PR은 draft로 연다. #77(`perf/k6-capacity-measurement`)의 권장 값으로 바꾸고 그 근거를 이 spec에 적은 뒤, 검증을 다시 돌리고 ready로 바꾼다.
- 테스트는 판별할 수 있는 작은 값을 테스트 안에서 준다.

**계약.** 새 HTTP 오류 code `RATE_LIMITED`(429) 하나와 응답 헤더 `Retry-After`가 생긴다. 기존 응답 모양, 이벤트, 토픽, DB 스키마는 바뀌지 않는다.

**복구.** 거절은 상태를 바꾸지 않으므로 복구할 것이 없다. 단말은 `Retry-After` 뒤에 같은 요청을 다시 보낸다.

**범위 조정(2026-10-21, 구현 중).** `@WebMvcTest`는 경로와 관계없이 모든 `HandlerInterceptor` 빈을 싣는다. 그래서 웹 슬라이스 테스트 12개가 모두 `TerminalAuthenticationInterceptor`를 만들고, 지금 `TerminalManagement`를 mock으로 받는다. 인터셉터가 limiter에 의존하면 12개 모두 limiter도 같은 방식으로 mock으로 받아야 한다. 점주 경로의 슬라이스 테스트(owner·store·reconciliation)를 변경 경로에 더한다. 동작은 바뀌지 않는다.

**범위 조정(2026-10-21, multi-review 반영).** Claude 80점, Codex 68점, spring-backend-specialist(P0 없음)였다. Gemini는 CLI가 없어 돌리지 않았다. 반영한 것:
- 세 리뷰어 공통: Redis 오류 WARN의 스택을 뺀다. 429 로그가 예외 줄을 붙여 두 줄이던 것을 한 줄로 고친다. 거절된 요청이 치르는 인증 조회·Redis·로그 비용을 한계로 적는다(결정 2·4·7).
- 둘 이상: Lettuce의 끊김 중 명령 버퍼를 `REJECT_COMMANDS`로 바꾼다(결정 4, `PlatformConfiguration`). 설정 검증에 무한대·NaN을 더한다. 두 키가 같은 slot인지 고정하는 테스트를 더한다. refill 테스트의 타이밍 여유를 없앤다. failover 뒤 시계 차이를 한계로 적는다.
- 하나: 상한·격리 조건과 k6 시나리오(결정 6), 원인 단말과 피해 단말의 구분(결정 7), 재전송 규약을 `RATE_LIMITED`에 묶기(결정 5), `RateLimitedException`의 1 이상 강제, 두 bucket이 모두 거절할 때·1초 미만 대기·키 TTL 테스트, 스크립트가 capacity와 refill을 한 번만 읽기.
- 받지 않은 것: fail-open 범위를 연결 실패·timeout으로 좁히자는 Codex 지적(결정 4에 이유). 비동기 dispatch에서 두 번 차감되는 문제(async handler가 없다). 다른 모듈 테스트가 terminal 내부 타입을 import하는 문제(인터셉터 의존이 더 늘 때 메타 애너테이션으로 모은다).

**범위 조정(2026-10-21, 남은 지적 보강).** 첫 리뷰의 점수(Claude 80, Codex 68)가 반영 전 코드 기준이라, 남은 지적을 고친 뒤 같은 리뷰어로 다시 받는다.
- Codex 1(High): fail-open 범위를 좁히는 대신 Redis가 오류로 답한 경우를 ERROR로 남긴다(결정 4).
- Codex 4: 설정에 상한(가득 차는 시간 하루, capacity 2^53)을 더한다(결정 4).
- Codex 2: edge 요청 상한을 외부 노출 전제조건으로 적는다(제외).
- Claude L6·specialist P4: failover 뒤 시계 차이를 한계로 두지 않고 스크립트에서 고친다(결정 3).
- Claude L3: 남은 타이밍 테스트를 고친다. 재전송 테스트는 2.1초를 쉬어 키 TTL(2초)이 지나므로 "거절이 토큰을 쓰지 않는다"를 판별하지 못하고 있었다. 상한 테스트는 쉬는 시간의 여유를 넓힌다.
- Claude L8: 다른 모듈의 웹 슬라이스 테스트가 terminal 내부 타입을 import하지 않게 terminal 테스트 패키지에 메타 애너테이션 하나를 둔다. 앞에서 미뤘던 것을 받는다.
- Claude L5: 비동기 dispatch에서 두 번 차감된다는 것을 인터셉터 KDoc에 적는다.
- Claude L9·specialist P1: 실제 Redis 컨테이너를 멈춘 뒤 요청이 timeout을 기다리지 않고 바로 통과하는지 재는 통합 테스트를 더한다. 설정 값 확인만으로는 끊김 동작을 증명하지 못한다.
- specialist P3: `RateLimitedException`을 만드는 곳을 구조 테스트로 limiter 하나에 고정한다(결정 5).
- 받지 않은 것: 아키텍처 wiki 머리말의 "기준은 main 808aed9"(그 페이지를 만든 시점의 표기이고 #100도 고치지 않았다).

**범위 조정(2026-10-21, 재리뷰 반영).** 재리뷰는 Claude 85(+5), Codex 73(+5), spring-backend-specialist P0·P1 없음(P2 1, P3 1, P4 4)이었다. 반영한 것:
- 사용자 결정 둘: Redis 오류 로그를 10초에 한 줄로 줄인다(Codex·Claude). 기동 때 스크립트를 돌려 설정 오류면 기동을 멈춘다(Codex High).
- specialist P2·Claude M1: `requestQueueSize`를 1000으로 묶어 단절 중 지연과 힙을 묶는다. keepalive는 기본값으로 이미 켜져 있음을 테스트로 고정한다. 단절 지속 시간과 미연결 pod의 줄 서기를 한계로 적는다.
- Claude L1·L2, specialist P4-1, Codex 3: 로그 수준을 근본 원인과 Redis 오류 코드로 가르고 줄에 근본 원인을 남긴다.
- Claude L3·L4, Codex 5: 토큰 계산 테스트를 sleep 대신 Redis hash를 준비하는 방식으로 바꾸고, TTL 하한을 좁히고, 끊김 테스트의 기준을 넉넉하게 바꾸고, 응답 없는 Redis 테스트를 더한다.
- Claude L5, specialist P4-2: 구조 테스트를 소유 타입 기준으로 바꾸고 `acquire` 호출 위치 규칙을 더한다.
- Claude L6·L8·M2, specialist P4-3·P4-4, Codex 1·6: ACL 명령 목록, 설정 오류 메시지의 값, 낡은 spec 문장, REJECT_COMMANDS의 점주 경로 부작용, edge 상한 전제조건을 추적되는 wiki(관측성)에 적는다.
- 받지 않은 것: Redis 키의 앱·환경 namespace(Codex 4, 기존 키 관례. 환경마다 Redis를 따로 둔다를 배포 확인에 적었다). `TCP_USER_TIMEOUT`(네이티브 transport 의존성). 미연결 pod의 연결 줄 서기 해소(기동 때 연결을 강제해야 해서 원칙과 어긋난다). 유효 토큰 폭주의 앱 안 차단(인증 결과 캐시는 폐기 즉시성과 충돌하고 토큰 digest 키는 Redis 키를 무한히 늘린다. edge가 맡는다).

**범위 조정(2026-10-21, 3차 리뷰 반영).** 3차는 Claude 82(-3, 기동 검사가 너무 넓었다), Codex 76(+3), spring-backend-specialist P0 없음(P1 1, P2 1, P3 2, P4 2)이었다. 반영한 것:
- 사용자 결정 둘: 연결 계열 실패 뒤 1초 생략창(Claude M1). 429 거절 로그를 limiter가 scope별 10초에 한 줄로 남김(Codex 3).
- Claude H1·specialist P2: 기동 검사를 앱 쪽 설정 오류로만 좁힌다. specialist P3: 기동 검사가 `IllegalStateException`을 삼키지 않는다.
- specialist P1: queue가 차면 Lettuce가 거절마다 남기는 스택 WARN을 로거 수준으로 막고 테스트로 고정한다(소스로 확인).
- Claude L2·specialist P4: 기동 검사와 운영이 키 형식·결과 해석을 같은 함수로 쓴다. Claude L3: 기동 검사가 실제 빈 생성에서 도는지 테스트한다. Claude L4: 구조 테스트를 이름과 소유 타입으로 맞춘다. Claude L5·L6·specialist P3: 로그 줄에 직전 시각을 넣고 시계가 뒤로 가면 바로 남긴다. Claude L7·specialist P4: keepalive 값을 고정한다. Claude L8: queue 값과 스레드 수의 관계를 적는다. Claude L9·L10·L11, specialist P4: 낡은 문장, ACL 키 패턴, 모르는 토큰의 401 경로, 15분과 얼린 컨테이너의 정확한 뜻을 고친다. Claude L1: 잠금 WARN의 부작용을 한계로 적는다.
- 받지 않은 것: 기동 뒤 설정 오류에 readiness를 내리거나 프로세스를 끝내는 것(Codex 2. Redis 문제로 모든 pod의 `/v1`이 멈춰 fail-open 결정과 충돌한다. ERROR가 10초에 한 줄 드러낸다). 인증 전 폭주의 앱 안 차단과 키 namespace(Codex 1·4, 위와 같은 이유). 생성자 인자를 `@ConfigurationProperties`로 묶는 것(값 넷이 한 클래스에서만 쓰여 @Value 선례를 따른다).

## 제외
- 인증 전 요청의 제한. 잘못된 토큰을 반복하는 요청은 단말을 모르므로 세지 않고, 요청마다 인증 조회 한 번을 그대로 치른다. 이런 요청은 edge(API gateway·ingress)가 IP 단위로 막는다(§5.1).
- 점주 경로(`/owner/**`)와 가입·로그인의 IP별 rate limit. 현재 상태가 외부 노출 조건으로 따로 적은 항목이다.
- 단말별 동시 요청 상한(§5.1의 concurrent sync). #101로 뺐다. 그 전까지 고장 단말의 동시 요청은 결정 6의 상한 조건(capacity 값)으로만 묶인다.
- 점주 단위와 전역 총량. 점주가 매장을 N개 만들면 상한도 N배다. edge가 맡는다.
- `Retry-After`를 무시하는 폭주의 사전 차단. 거절된 요청도 인증 조회 1회와 Redis 왕복 1회를 치르며, edge가 `Authorization` 해시나 IP 단위로 막는다. 이 edge 요청 상한은 단말 API를 외부에 노출하기 전의 전제조건이다. 머지할 때 현재 상태(볼트)의 외부 노출 조건에 더한다.
- endpoint별 bucket과 sync 항목 수 가중. 결정 2.
- 1초 생략창 이상의 circuit breaker(실패율 집계, half-open 탐침). 결정 4.
- Redis 장애(fail-open) 횟수 metric. 결정 7.
- outbound(provider) rate limit과 bulkhead.
- 시연 화면(`static/terminal`)의 `Retry-After` 처리. 지금도 전송에 실패하면 5초 뒤 다시 보낸다.
- 한계: 패킷이 사라지는 단절(RST 없음)에서는 Lettuce가 끊김을 몰라 `REJECT_COMMANDS`가 듣지 않는다. 응답 없는 명령 1000개까지는 timeout 1초씩 기다린 뒤 통과하고, 그 뒤로는 바로 통과한다. 연결은 커널 재전송 한도(Linux 기본 약 15분)가 지나야 끊기므로, 이전 주소가 계속 응답하지 않으면(Redis가 다른 주소로 옮겨 감) 그동안 보호가 없다. 같은 주소로 네트워크가 돌아오면 다음 재전송(최대 2분) 안에 회복한다. 줄이려면 pod의 `net.ipv4.tcp_retries2`를 낮추거나 네이티브 transport로 `TCP_USER_TIMEOUT`을 건다(배포 결정).
- 한계: 한 번도 연결되지 못한 pod는 동시 요청이 connect timeout씩 줄을 선다(결정 4).
- 배포 확인: health probe가 요청 스레드 풀과 함께 굶지 않게 `management.server.port`를 분리하는 것을 배포에서 검토한다(Redis가 느린 동안 `/v1` 요청이 스레드를 1초씩 쥔다).
- 배포 확인: 운영 Redis의 `maxmemory-policy`와 앱 계정 ACL(EVAL·EVALSHA 허용). ACL은 EVAL·EVALSHA와 스크립트 안 명령(TIME·HMGET·HSET·PEXPIRE)을 허용해야 한다. 빠지면 기동 검사가 기동을 멈춘다. 운영 중에 바뀌어 NOPERM이나 `noeviction`의 OOM이 나면 fail-open이 이어지고 ERROR가 10초에 한 줄 남는다.
- 배포 확인: 키에 환경 구분이 없으므로 환경마다 Redis(또는 DB 번호)를 따로 둔다. 세션·잠금·로그인 카운터 키도 같은 관례라 rate limit 키에만 namespace를 붙이지 않는다(재리뷰 Codex 지적을 받지 않은 이유). eviction 정책이면 bucket 키가 밀려 가득 찬 상태로 돌아간다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/web/TerminalAuthenticationInterceptor.kt
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/redis/**
- src/main/kotlin/com/resilientpos/platform/ErrorType.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/RateLimitedException.kt
- src/main/kotlin/com/resilientpos/platform/PlatformConfiguration.kt
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
- README.md

## 인수 조건
1. 단말 bucket에 토큰이 없으면 인증된 `/v1` 요청은 429 `RATE_LIMITED`와 `Retry-After`를 받고 Controller에 닿지 않는다.
2. 매장 bucket이 비면 같은 매장의 다른 단말도 429다. 다른 매장의 단말은 영향을 받지 않는다.
3. 한쪽 bucket이 거절하면 다른 쪽 bucket의 토큰은 줄지 않는다.
4. 거절된 요청은 토큰을 쓰지 않는다. 같은 bucket을 쓰는 다른 요청이 없으면 `Retry-After`만큼 기다린 뒤 같은 요청이 통과한다.
5. capacity만큼은 연달아 통과하고 그다음 요청이 거절된다. 쉬면 refill 속도로 다시 차고 capacity를 넘지 않는다.
6. `Retry-After`는 토큰 1개를 다시 얻기까지의 시간을 초로 올림한 값이고 최소 1이다. 두 bucket이 모두 거절하면 더 오래 기다려야 하는 쪽의 시간과 scope다.
7. 연결 실패·timeout·끊긴 동안의 거절·기다리면 풀리는 Redis 오류 응답이면 요청은 통과하고 스택 없는 WARN이 남는다. 같은 수준의 줄은 10초에 한 줄이고 그 사이 통과 건수를 함께 적는다.
8. 인증에 실패한 요청은 limiter를 부르지 않고 401이다.
9. 거절마다 `inbound_rate_limited_total{scope}`가 1 오른다. 거절 INFO는 scope별 10초에 한 줄이고 scope·terminalId·storeId·건수·직전 시각을 담는다. advice는 429를 남기지 않는다. 토큰과 `Authorization` 값은 남지 않는다.
10. capacity가 1 미만이거나 refill이 0 이하·무한대·NaN이면, 또는 빈 bucket이 가득 차는 시간이 하루를 넘거나 capacity가 2^53을 넘으면 기동하지 못한다.
11. 429 응답을 REST Docs 스니펫으로 문서화한다.
12. 상한 값이 #77의 권장 값이고 그 근거가 이 spec에 있다. ready로 바꾸기 전 조건이다.
13. 기존 테스트가 하나도 깨지지 않는다.
14. 앱의 Lettuce 클라이언트는 끊긴 연결에서 명령을 버퍼에 받지 않고 바로 거절한다(`REJECT_COMMANDS`). Boot가 넣는 connect timeout은 그대로다.
15. 단말 키와 매장 키는 같은 Redis Cluster slot에 있고, 키에는 빈 bucket이 가득 차는 시간(ms 올림)만큼 TTL이 걸린다.
16. Redis가 기다려도 풀리지 않는 오류로 답하면(스크립트 오류·NOPERM·OOM 등, 연결 단계의 WRONGPASS 포함) 통과하되 ERROR가 남는다. 줄에는 근본 원인의 클래스와 메시지가 있다.
17. 저장된 시각이 Redis의 지금보다 미래여도 refill이 멈추지 않는다.
18. 실제 Redis가 내려가면 요청은 timeout을 기다리지 않고 바로 통과한다.
19. `RateLimitedException`은 limiter만 만들고, `acquire`는 인증 인터셉터만 부른다.
20. 기동 때 스크립트가 앱 쪽 설정 오류(NOPERM·NOAUTH·WRONGPASS·CROSSSLOT·WRONGTYPE·스크립트 오류·막힌 명령)로 실패하면 기동하지 못한다. Redis에 닿지 못하거나 OOM·READONLY·연결 수 초과면 WARN을 남기고 뜬다. 이 검사는 실제 빈 생성에서 돈다.
21. 응답 없는 Redis(얼린 컨테이너. 연결은 살아 있고 응답만 없다)에서 묶은 queue가 차면 다음 요청은 timeout을 기다리지 않고 통과하고, Lettuce의 `Unexpected exception during request` WARN은 남지 않는다. 앱 설정의 `requestQueueSize`는 1000이고 keepalive는 켜져 있으며 13초·13초·3회다.
22. 연결 계열 실패 뒤 1초 동안은 Redis를 부르지 않고 통과하고, 1초가 지나면 다시 부른다. 설정 오류로 실패한 뒤에는 건너뛰지 않는다.
23. 형식은 맞지만 모르는 토큰이면 limiter를 부르지 않고 401이다.

## 검증
- `./gradlew test --tests 'com.resilientpos.terminal.*'` — 인터셉터와 Lua 스크립트. 스크립트는 Testcontainers Redis로 실제 실행한다.
- `./gradlew test` — 전체 통과. `/v1` 슬라이스 테스트가 limiter를 받아도 깨지지 않는다.
- 실패 시나리오: Redis 오류를 던지는 mock으로 fail-open과 로그 한 줄, 범위 밖 설정으로 생성 실패, 매장 거절 때 단말 토큰 보존. 끊긴 연결의 즉시 거절과 응답 없는 Redis의 queue 상한은 실제 컨테이너를 멈추거나 일시정지해 잰다(`RedisTerminalRateLimiterOutageIntegrationTest`). 토큰 계산 테스트는 sleep 대신 Redis hash를 직접 준비해 타이밍에 기대지 않는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), 제출 전에 `--worktree` 없이 한 번 더.
- PR 전 `resilientpos-multi-review`. 신뢰 경계의 입력 제한이라 보안 관점과 `spring-backend-specialist`를 넣는다.

## Wiki 영향
- [아키텍처](../wiki/architecture.md) — 요청 흐름 1번(인증) 뒤에 빈도 상한 단계와 429 재시도 규약을 넣는다. §6.4 구조 테스트 표에 rate limit 규칙 둘을 더한다.
- [관측성](../wiki/observability.md) — counter `inbound_rate_limited_total{scope}`, 거절 INFO 로그, Redis 오류 WARN·ERROR(10초에 한 줄), 기동 검사, ACL 명령, 환경별 Redis 분리, edge 상한 전제조건을 적는다.
- [README](../../README.md) 아키텍처 절 — "Redis는 두 가지에만 씁니다"에 세 번째 쓰임으로 단말·매장 빈도 상한(fail-open)을 더한다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트) — "속도 제한(rate limit)도 없다"를 이 PR의 상태로 바꾸고 Redis 쓰임에 단말 rate limit을 더한다. 볼트 파일이라 이 PR diff에는 없다. **머지할 때 고친다.** 볼트는 모든 worktree가 main의 현재 상태로 읽는다. 이 PR이 #77을 기다리며 draft로 머무는 동안 먼저 적으면, 다른 작업(특히 #77의 k6 측정)이 main에 상한이 있다고 잘못 읽는다.
- `application.yaml`의 Redis 주석 — 쓰는 곳에 빈도 상한을 더하고, "단말 경로는 Redis 장애에 영향이 없다"를 "막히지 않는다(잠금과 빈도 상한은 fail-open)"로 고친다.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트) — 무영향. Kafka·Modulith 작업이 아니다.
- 요구사항 변화: 인증된 단말 요청에 단말·매장 단위 빈도 상한이 생기고, 넘으면 429와 `Retry-After`다. 단말은 `Retry-After` 뒤 같은 요청을 다시 보낸다.

## 분할 계획
- 이 PR은 inbound 빈도 상한 하나의 개발 컨텍스트다. 상한 값의 실측은 #77(`perf/k6-capacity-measurement`)의 몫이고, 이 PR은 draft로 그 결과를 기다린다(결정 8).
- 겹침 확인(2026-10-21, origin/main `c7810af`): 열린 PR은 #94(draft, `refactor/modulith-2-1-2-resubmit`)뿐이고 그 경로(`build.gradle.kts`, `EventPublicationResubmitter`와 그 테스트, `domain-model.md`, 자기 spec)는 이 PR과 겹치지 않는다. 진행 중이지만 아직 push되지 않은 브랜치 중 `feature/platform-operator-dlt-redrive`(#91)는 `ApiControllerAdvice`·`ErrorType`·보안 설정을, `feature/observability-alerts-tracing`(#76)은 `observability.md`와 metric 규칙을 고칠 수 있다. push·PR 생성·머지 직전에 다시 확인하고 먼저 머지된 쪽에 맞춰 rebase한다.
- 공용 헬퍼: main과 #94에 rate limiter나 429 처리가 없다.
- #106·#107 머지 뒤 rebase(2026-10-21, origin/main `8221d79`): 줄 단위 충돌은 셋이었다. `ApiControllerAdvice`는 429 처리와 #107이 고친 403 주석을 함께 두었다. `PlatformConfiguration`은 두 import를 모두 두었다. `observability.md`는 #106·#107 본문을 두고 이 PR의 "단말 빈도 상한" 항목과 Redis 항목의 fail-open 문장을 다시 넣었다. #107의 `DeadLetterControllerTest`도 `@WebMvcTest`라 단말 인증 인터셉터를 싣는다. 그래서 다른 슬라이스 테스트처럼 limiter mock을 더했다(슬라이스 13개). #106 spec이 이 PR에 남긴 두 가지는 이렇다. 두 관측 항목을 모두 살렸다. `inbound_rate_limited_total`의 알림 규칙은 상한 값이 자리값인 동안 더하지 않는다(결정 8). #77 값으로 바꿀 때 `docker/prometheus/alerts.yaml`에 둘지 정한다. 429와 fail-open은 #106 `OwnerSessionStoreUnavailable`(503)과 #107의 `/admin` 503 경로를 바꾸지 않는다.

## TDD 검증 기록

limiter의 token bucket 동작은 Lua 스크립트 하나가 함께 구현한다. 그래서 첫 동작의 GREEN 뒤에 더한 테스트는 처음부터 통과했다. 각 테스트가 실제로 결함을 잡는지는 스크립트에 그 결함 하나를 일부러 넣어 실패를 본 뒤 원복하는 방식으로 확인했다(아래 "판별력"). 명령은 모두 `./gradlew test --tests 'com.resilientpos.terminal.internal.adapter.redis.*'`(아래 "limiter 명령")다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `단말 bucket은 capacity만큼 연달아 통과시키고 다음 요청을 거절한다` | limiter 명령. `acquire`가 빈 껍데기라 네 번째 요청에서 `RateLimitedException`이 나지 않아 AssertionFailedError | 같은 명령. 두 키 token bucket Lua 스크립트를 넣은 뒤 통과(1개) | limiter 명령 통과(9개) |
| `쉬면 refill 속도만큼 다시 찬다` | 처음부터 통과(characterization). 판별력: 경과 시간만큼 채우는 항을 지운 결함으로 FAILED | 같은 명령 통과 | limiter 명령 통과(9개) |
| `capacity를 넘어 차지 않는다` | 처음부터 통과. 판별력: 첫 판은 0.3초를 쉬어 키 TTL(빈 bucket이 가득 차는 시간, 0.1초)이 지나 상한을 지운 결함을 잡지 못했다. 토큰이 남은 bucket을 TTL 안에서만 쉬게 고친 뒤 같은 결함으로 FAILED | 같은 명령 통과 | limiter 명령 통과(9개) |
| `매장 bucket은 그 매장 단말 전부가 나눠 쓰고 다른 매장은 영향을 받지 않는다` | 처음부터 통과. 판별력: 매장 키를 단말마다 따로 두는 결함으로 FAILED | 같은 명령 통과 | limiter 명령 통과(9개) |
| `매장 bucket이 거절하면 단말 bucket의 토큰을 차감하지 않는다` | 처음부터 통과. 판별력: 다른 bucket을 보기 전에 토큰을 가져가는 결함으로 FAILED | 같은 명령 통과 | limiter 명령 통과(9개) |
| `거절은 토큰을 쓰지 않고 Retry-After는 토큰 1개를 다시 얻는 시간을 초로 올림한다` | 처음부터 통과. 판별력: 거절도 토큰을 쓰는 결함으로 FAILED | 같은 명령 통과 | limiter 명령 통과(9개) |
| `Redis 오류면 제한 없이 통과시키고 WARN을 남긴다` | limiter 명령. `RedisConnectionFailureException`이 그대로 올라와 FAILED | 같은 명령. `DataAccessException`·`IllegalStateException`을 잡아 WARN을 남기고 반환한 뒤 통과 | limiter 명령 통과(9개) |
| `capacity가 1 미만이거나 refill이 0 이하이면 만들지 못한다` | limiter 명령. 범위 밖 값으로도 만들어져 AssertionFailedError | 같은 명령. 생성 때 `require`를 넣은 뒤 통과 | limiter 명령 통과(9개) |
| `거절은 거절한 bucket의 scope로 counter를 올리고 두 scope는 처음부터 0으로 있다` | limiter 명령. 생성 직후 counter가 등록되지 않아 AssertionFailedError | 같은 명령. 생성 때 두 scope 등록, 거절 때 증가를 넣은 뒤 통과 | limiter 명령 통과(9개) |
| `TerminalAuthenticationInterceptorTest`의 `limiter가 거절하면 429 RATE_LIMITED와 Retry-After이고 Controller에 닿지 않으며 INFO 한 줄에 토큰이 남지 않는다`(tdd-driver) | `./gradlew test --tests 'com.resilientpos.terminal.internal.adapter.web.TerminalAuthenticationInterceptorTest'`. 인터셉터가 limiter를 부르지 않아 429가 아니라 200이라 FAILED | 같은 명령. 인터셉터의 `acquire` 한 줄, `ErrorType.RATE_LIMITED`(INFO), advice의 429·`Retry-After` 처리를 넣은 뒤 통과. 로그 줄이 INFO이고 scope·terminalId·storeId를 담으며 토큰이 없다 | `./gradlew test --tests 'com.resilientpos.*.internal.adapter.web.*' --tests 'com.resilientpos.platform.*'` 통과 |
| `SyncControllerTest`의 `limiter가 거절하면 429 RATE_LIMITED와 Retry-After이고 Facade를 부르지 않는다`(tdd-driver) | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.SyncControllerTest'`. 상태 429 기대에 200이라 FAILED | 같은 명령 통과. REST Docs 스니펫 `sync-receive-batch-rate-limited`(응답 헤더 `Retry-After` 포함)가 `build/generated-snippets`에 생겼다 | 위 회귀 명령 통과 |
| 기존 `형식이 토큰과 다른 값은 authenticate를 부르지 않고 같은 401이다`에 limiter 무상호작용 확인을 더함 | 처음부터 통과(characterization). 인증 실패 경로는 원래 limiter를 부르지 않고, limiter는 인증 결과를 인자로 받아 앞에 둘 수 없다 | 같은 명령 통과 | 위 회귀 명령 통과 |
| 웹 슬라이스 테스트 12개에 limiter mock을 더함 | 해당 없음(동작 변경 아님). 인터셉터가 limiter를 받으면서 모든 `@WebMvcTest`가 그 빈을 필요로 한다 | — | 위 회귀 명령 통과 |

전체 회귀: `./gradlew test` 통과(724개, 실패 0).

multi-review 반영분이다. 대상 명령은 limiter 명령에 `--tests 'com.resilientpos.terminal.internal.adapter.web.TerminalAuthenticationInterceptorTest' --tests 'com.resilientpos.platform.RateLimitedExceptionTest' --tests 'com.resilientpos.platform.ResourceLimitConfigurationTest'`를 더한 것("반영 명령")이다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `Redis 오류면 제한 없이 통과시키고 스택 없는 WARN 한 줄을 남긴다` | 반영 명령. WARN에 스택 프레임이 붙어 FAILED. 첫 판은 캡처 구간 전체가 한 줄이길 요구해 kotlin-logging의 초기화 안내 줄에도 걸렸다. 스택 프레임과 예외 줄이 없는지를 보도록 고친 뒤, 스택을 다시 붙인 구현에서 FAILED를 다시 확인했다 | 같은 명령. 예외를 넘기지 않고 클래스·메시지만 붙인 한 줄로 바꾼 뒤 통과 | 전체 통과 |
| `capacity가 1 미만이거나 refill이 0 이하·무한대·NaN이면 만들지 못한다` | 반영 명령. 무한대 refill로도 만들어져 FAILED | 같은 명령. 유한값 조건을 더한 뒤 통과 | 전체 통과 |
| `RateLimitedExceptionTest`의 `Retry-After가 1 미만이면 만들지 못한다` | 반영 명령. 0으로도 만들어져 FAILED | 같은 명령. 생성 때 1 이상 조건을 더한 뒤 통과 | 전체 통과 |
| 인터셉터 테스트의 거절 로그에 예외 줄이 없다는 확인 | 반영 명령. advice가 예외를 cause로 넘겨 `RateLimitedException` 줄이 따로 찍혀 FAILED | 같은 명령. cause 없이 메시지를 로그 줄에 붙인 뒤 통과 | 전체 통과 |
| `ResourceLimitConfigurationTest`의 `Redis 연결이 끊긴 동안 명령을 버퍼에 쌓지 않고 바로 거절하며 connect timeout은 그대로다` | 반영 명령. 기본값 `DEFAULT`라 FAILED | 같은 명령. `LettuceClientOptionsBuilderCustomizer`로 `REJECT_COMMANDS`를 건 뒤 통과. connect timeout 1초가 남는 것도 확인 | 전체 통과 |
| `두 bucket이 모두 거절하면 더 오래 기다려야 하는 쪽의 시간과 scope다` | 처음부터 통과. 판별력: 먼저 거절한 bucket을 고르는 결함으로 FAILED | limiter 명령 통과 | 전체 통과 |
| `두 키는 같은 Cluster slot에 있고 빈 bucket이 가득 차는 시간 이하의 TTL이 걸린다` | 처음부터 통과. 판별력: 키에서 hash tag를 뺀 결함, TTL을 걸지 않는 결함 각각으로 FAILED | limiter 명령 통과 | 전체 통과 |
| `쉬면 refill 속도만큼 다시 차고 1초 미만 대기의 Retry-After는 1이다`(타이밍 여유 보강) | 판별력: capacity 1로 바꾸자 토큰 1개가 차는 시간이 키 TTL과 같아져, refill을 지운 결함을 잡지 못했다. capacity 2·초당 2개(토큰 0.5초, TTL 1초)로 0.6초를 쉬게 고친 뒤 같은 결함으로 FAILED | limiter 명령 통과 | 전체 통과 |

리뷰 반영 뒤 고친 스크립트에 결함 7종(먼저 거절한 쪽 선택, TTL 없음, hash tag 없음, refill 없음, 원자성 깨짐, 거절도 차감, 상한 없음)을 다시 넣어 각각 해당 테스트가 FAILED임을 확인했다. 전체 회귀: `./gradlew test` 통과(729개, 실패 0).

남은 지적 보강분이다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `Redis가 명령에 오류로 답하면 통과시키되 ERROR 한 줄로 보호가 꺼졌음을 남긴다` | limiter 명령. WARN으로 남아 FAILED | 같은 명령. 원인이 `RedisCommandExecutionException`인 `RedisSystemException`만 ERROR로 가른 뒤 통과. 끊긴 동안의 거절(`RedisException`이 원인)은 기존 WARN 테스트에 더해 WARN임을 확인 | 아래 회귀 |
| `빈 bucket이 가득 차는 데 하루를 넘거나 capacity가 2의 53제곱을 넘으면 만들지 못하고 딱 하루는 된다` | limiter 명령. 하루가 넘는 값으로도 만들어져 FAILED | 같은 명령. 생성 때 상한을 넣은 뒤 통과. 통합 테스트의 기본 refill(초당 0.001개, 가득 차는 데 100만 초)이 새 검증에 걸려 초당 0.02개로 바꿨다(테스트 몇 초 동안 1개도 차지 않는다는 뜻은 같다) | 아래 회귀 |
| `저장된 시각이 Redis의 지금보다 미래여도 refill이 멈추지 않는다` | limiter 명령. 미래 시각이 그대로라 0.6초 뒤에도 RateLimitedException | 같은 명령. 스크립트가 미래 시각을 지금으로 고쳐 쓴 뒤 통과. 판별력: 그 분기를 지운 결함으로 FAILED | 아래 회귀 |
| `거절은 토큰을 쓰지 않고 Retry-After는 토큰 1개를 다시 얻는 시간을 초로 올림한다`(TTL 보강) | 판별력 점검에서 찾았다. 2.1초를 쉬어 키 TTL(2초)이 지나 마지막 확인이 결함과 관계없이 통과했다(잡은 것은 Retry-After 값뿐이었다). capacity 2·초당 2/3개(TTL 3초)로 1.6초만 쉬게 고친 뒤 거절도 차감하는 결함·refill 없음 결함으로 FAILED | limiter 명령 통과 | 아래 회귀 |
| `capacity를 넘어 차지 않는다`(여유 보강) | 초당 2개·1.2초로 바꿔 쉬는 시간의 여유를 200ms·300ms로 넓혔다. 상한 없음 결함으로 FAILED | limiter 명령 통과 | 아래 회귀 |
| `RedisTerminalRateLimiterOutageIntegrationTest`의 `Redis가 내려가면 timeout을 기다리지 않고 바로 통과한다` | 처음부터 통과(REJECT_COMMANDS가 이미 있다). 판별력: 설정을 기본값(`DEFAULT`)으로 되돌린 결함에서 500ms를 넘겨 FAILED. 리뷰어들이 추론으로 짚은 "끊긴 동안 요청마다 timeout을 기다린다"를 실측으로 확인했다 | limiter 명령 통과(0.58초) | 아래 회귀 |
| `RateLimitedException은 handler 전에 판정하는 빈도 상한 limiter만 만든다`(ArchUnit) | 처음부터 통과. 판별력: advice에 이 예외를 만드는 함수를 넣은 결함으로 FAILED | `./gradlew test --tests 'com.resilientpos.platform.RateLimitedExceptionTest'` 통과 | 아래 회귀 |
| 다른 모듈 웹 슬라이스 테스트 10개가 `@MockTerminalRateLimiter`로 limiter를 받는다 | 해당 없음(테스트 구조 변경). 타입 수준 `@MockitoBean(types = ...)`을 메타 애너테이션으로 쓴다. sync의 429 문서 테스트는 `rejectByRateLimit`로 거절을 흉내 낸다 | 웹 슬라이스 묶음 통과 | 아래 회귀 |

회귀: 웹 슬라이스·구조 테스트 묶음(`./gradlew test --tests '*ControllerTest' --tests '*AuthenticationInterceptorTest' --tests 'com.resilientpos.platform.*'` 일부) 109개 통과. 전체 `./gradlew test`는 #77 k6 측정과 CPU를 함께 쓰지 않도록 측정이 끝난 뒤 돌린다.

## 결과
- 인증된 `/v1/**` 요청은 `TerminalAuthenticationInterceptor`가 `authenticate` 직후 부르는 `RedisTerminalRateLimiter`를 통과해야 Controller에 닿는다. 단말 bucket과 매장 bucket을 Lua 스크립트 한 번으로 확인하고 둘 다 남을 때만 둘 다 차감한다. 넘으면 429 `RATE_LIMITED`와 `Retry-After`(정수 초)이고, 거절은 `inbound_rate_limited_total{scope}`와 INFO 한 줄로 남는다.
- Redis 오류면 통과시키고 근본 원인을 담은 WARN(기다리면 풀림)이나 ERROR(사람이 고쳐야 함)를 10초에 한 줄 남긴다. 연결 계열 실패 뒤 1초는 Redis를 부르지 않는다. Lettuce는 연결이 끊긴 동안 명령을 바로 거절하고(`REJECT_COMMANDS`) 응답 없는 명령을 1000개까지만 받는다. 기동 때 스크립트를 한 번 돌려 앱 쪽 설정 오류면 기동을 멈춘다.
- 검증: 전체 회귀는 위 TDD 기록의 마지막 줄에 있다. limiter 테스트는 스크립트·설정에 결함을 넣어 각각 잡히는지 확인했다.
- multi-review(Gemini는 CLI가 없어 미실행): 1차 Claude 80·Codex 68·specialist P0 없음, 2차 Claude 85·Codex 73·specialist P0·P1 없음, 3차 Claude 82·Codex 76·specialist P0 없음(P1 1). 반영과 받지 않은 것은 범위의 범위 조정 절들에 있다. 단말별 동시 요청 상한은 #101로 뺐다.
- 남은 것: 상한 값은 아직 자리값이다(인수 조건 12). #77의 권장 값과 근거를 결정 8에 적고 검증을 다시 돌린 뒤 ready로 바꾼다. 현재 상태(볼트)는 머지할 때 고친다.
