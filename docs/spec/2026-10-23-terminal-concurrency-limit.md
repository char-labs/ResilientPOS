# PR spec: 단말별 동시 요청 상한으로 잠금 대기 중 커넥션 점유를 막는다

## 목적
#87(PR #102)의 빈도 상한(token bucket)은 단말이 요청을 **얼마나 자주** 보내는지만 세고 **한꺼번에 몇 개**를 보내는지는 막지 못한다. 쉬었다 돌아온 단말은 bucket이 가득 차 있어 capacity(10)만큼을 한꺼번에 보낼 수 있다. 고장 단말이 같은 sync 배치를 동시에 보내면 요청마다 커넥션을 하나씩 잡고 같은 watermark 행 잠금 앞에 줄을 선다. 잠금을 쥔 하나만 일하고 나머지는 커넥션을 쥔 채 기다린다. checkout도 같은 주문 행 잠금에서 같다. #77 측정(단말 하나가 같은 배치를 초마다 10개 동시에, 바탕 70 RPS, limiter 없음)에서 Hikari active가 10/10까지 찼고 pending이 최대 14였다. 그래서 [inbound-rate-limit](2026-10-21-inbound-rate-limit.md) 결정 6의 상한 조건(한 단말이 한꺼번에 쥐는 커넥션 수)이 어떤 값으로도 성립하지 않았고, 그 spec이 이 일을 #101로 넘겼다. 근거는 외부 연동 전략 §5.1(`docs/references/04_External_Integration_Strategy.md`)의 "concurrent sync도 제한한다"이다.

기대 결과:
- 한 단말이 pod 하나에서 동시에 처리받는 쓰기 요청은 sync 1개와 그 밖 1개까지다. 넘는 요청은 handler에 닿기 전에 429 `RATE_LIMITED`와 `Retry-After: 1`을 받고, 인증 조회 한 번 말고는 DB 커넥션을 쓰지 않는다. 결제 시도 조회(GET)는 묶지 않는다.
- 배치를 차례로 보내는 정상 단말은 거절되지 않고, backlog sync와 실시간 checkout이 서로를 막지 않는다.
- 같은 고장 단말 시나리오를 바꾸기 전과 후에 재서, Hikari pending과 다른 단말의 sync 지연·실패율이 바탕 부하만 건 실행과 같은 수준으로 돌아온 것을 수치로 남긴다.

## 범위
소유 모듈은 `terminal`이다. 상한은 인증 인터셉터(`TerminalAuthenticationInterceptor`)가 부르는 새 limiter(`terminal.internal.adapter.web`)가 판정한다. `platform`은 바뀌지 않고 `RateLimitedException`과 advice의 KDoc만 넓힌다. 업무 사실이 아니라 시한이 있는 보호 장치라 도메인 port를 두지 않는다(#87과 같다).

**결정 1: 행 잠금 옵션이 아니라 단말별 in-flight 상한으로 막는다.**
- 문제: 같은 단말의 겹친 요청이 잠금을 기다리며 커넥션을 쥔다. 한 단말이 pod의 풀(10개)을 다 쥐면 다른 매장 요청이 커넥션을 기다리다 실패한다(Hikari `connection-timeout` 3초).
- 제약: 행 잠금은 `JdslQueryExecutor.findFirstForUpdate`에서 대기 3초(`lock_timeout`)로 잡는다. sync 배치는 항목마다 트랜잭션을 따로 커밋하므로(`open-in-view: false`) 같은 단말의 배치 둘은 항목 사이사이로 번갈아 watermark 잠금을 잡는다. 주문 행 잠금은 단말 요청(HTTP checkout, sync의 `REQUEST_CHECKOUT`)만이 아니라 Kafka listener의 결제 반영(`OrderCommandFacade.markPaid`)도 잡는다.
- 수단: 인증된 단말의 처리 중 요청 수를 묶음별로 1개로 묶고, 넘으면 handler 전에 거절한다. 기다리는 요청 자체가 생기지 않으므로 커넥션을 쥔 대기도 없다. 이슈의 두 장면(같은 배치를 동시에, 같은 주문 checkout을 동시에)을 한 장치로 막는다.
- 버린 것:
  - 행 잠금을 `NOWAIT`으로 잡기. PostgreSQL은 `NOWAIT`이면 행을 바로 잠글 수 없을 때 기다리지 않고 오류를 낸다([SELECT의 locking clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)). 그런데 항목 사이로 번갈아 잡는 sync에서는 늦게 온 배치만이 아니라 먼저 온 정상 배치도 중간에 실패할 수 있다. 결제 반영 listener와 단말 checkout 재전송이 몇 ms 겹치는 정상 경합도 409가 된다. 처리 도중 거절이라 409 `CONCURRENT_REQUEST`는 "같은 요청을 그대로 다시 보내도 되는지"를 약속하지 않으므로(advice KDoc) 재시도 규약도 endpoint마다 갈린다.
  - 짧은 `lock_timeout`. 기다리는 시간만 줄이고 기다리는 요청 수는 줄이지 않는다. 앞 요청이 ms 단위로 끝나므로 동시에 온 요청 10개는 저마다 짧은 한도 안에 잠금을 얻고, 그동안 9개가 계속 커넥션을 쥐고 줄을 선다. 상한 조건을 지키지 못한다.
- 행 잠금 대기 3초는 그대로 둔다. 서로 다른 단말·listener 사이의 정상 경합을 위한 값이다.

**결정 2: pod 메모리에서 센다.**
- 수단: limiter가 `ConcurrentHashMap` 기반 집합에 (단말 ID, 묶음)을 넣는다. 넣기에 성공하면 자리를 얻은 것이고, 이미 있으면 거절한다. 요청이 끝나면 뺀다. 상한이 1이라 개수가 아니라 있음·없음만 본다. 빈 단말의 항목은 남지 않는다.
- 근거: 지키려는 자원(Hikari 풀)은 pod마다 따로 있다. pod마다 세면 각 풀에서 한 단말이 쥐는 커넥션이 정확히 묶인다. #87 결정 3이 pod 메모리를 버린 이유(실제 상한이 "pod 수 × 설정값"이라 매장 총량을 묶지 못한다)는 공유 총량을 묶는 빈도 상한의 이유라 여기에 해당하지 않는다.
- 버린 것: Redis 카운터. pod 사이에 공유되지만, 해제 전에 pod가 죽으면 카운터가 남아 그 단말이 TTL 동안 거절만 받는다. TTL은 배치의 최장 시간(#77에서 39초)보다 길어야 한다. 요청마다 만료 시각을 두는 semaphore(ZSET Lua)로 줄일 수 있지만 스크립트가 하나 더 생기고, 요청마다 Redis 왕복(해제)이 하나 더 들며, 장애 정책도 다시 정해야 한다.
- 치르는 것: 단말 하나가 DB 전체에서 쥐는 커넥션은 최대 "pod 수 × 2"다. pod마다는 2개다. pod가 재시작하면 집합도 비지만, 그 pod에서 처리 중이던 요청도 함께 사라지므로 맞는 상태다.
- Redis를 쓰지 않으므로 Redis 장애 정책(fail-open·fail-closed)은 해당하지 않는다.

**결정 3: 묶음은 sync와 그 밖 둘이고 상한은 각각 1이다. 상수로 둔다.**
- sync는 `POST /v1/terminals/{terminalId}/sync`다. 판별은 handler mapping이 고른 경로 pattern(`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`)이 그 pattern과 같은지로 한다. 그 밖은 sync가 아닌 `/v1/**` 쓰기 요청(`POST /v1/orders`, `POST /v1/orders/{orderId}/checkout`, `POST /v1/payment-attempts/{paymentId}/retry`)이다.
- GET(`GET /v1/orders/{orderId}/payment-attempts`)은 자리를 잡지 않는다. 행 잠금을 잡지 않는 읽기라(PostgreSQL MVCC 읽기는 `FOR UPDATE`에 막히지 않는다) 잠금 앞에서 커넥션을 쥐고 기다리는 일이 없다. 묶으면 결제 상태를 폴링하는 동안 다음 주문을 만들거나, 클라이언트가 GET을 자동 재시도할 때 막을 것 없이 429만 난다. 대가로 GET 폭주는 빈도 상한만 막는다.
- sync를 1로 두는 것은 프로토콜 때문이다. 단말은 응답의 `lastAckedSequence` 다음부터 이어 보내므로 같은 단말의 배치는 차례로 온다. 조정할 값이 아니어서 설정으로 열지 않는다.
- 묶음을 나누는 이유: 한 묶음으로 두면, 오프라인에서 돌아온 단말의 backlog sync(부하가 걸리면 수십 초)가 도는 동안 그 단말의 실시간 checkout이 거절된다. 단말이 5초에 끊고 같은 배치를 다시 보내면 sync가 둘이 돌면서 그동안 checkout이 계속 막힌다. 나누면 다시 보낸 sync만 거절되고 checkout은 지나간다.
- 버린 것: `/v1` 전체를 한 묶음으로 세고 상한을 2로 두는 안. 경로 판별이 없지만 위의 재전송 경우에 실시간 checkout이 막힌다.
- 치르는 것: 한 단말이 pod 하나의 풀에서 handler 단계에 쥐는 커넥션은 최대 2개(풀 10의 20%)다. #87 결정 8이 상한 조건을 계산할 때 가정한 단말 몫(10%, 1개)보다 크다. 한 pod에 고장 단말 k대가 몰리면 2k개다. 자리를 잡기 전의 인증 조회(DB 읽기 한 번)는 거절된 요청도 치르고, 결제 시도 조회는 짧은 읽기라 이 수에 들지 않는다.
- 같은 단말이 쓰기 요청을 겹쳐 보내면(주문 생성 중에 다른 주문의 checkout) 겹친 쪽이 429다. 시연 단말은 sync만 쓰고 k6 바탕 부하는 단말마다 요청을 차례로 보낸다(`ownedTerminals`가 단말을 VU 하나에만 맡긴다).

**결정 4: 순서는 인증 → 동시 상한 → 빈도 상한이다. 자리는 요청이 끝날 때 푼다.**
- 동시 상한을 빈도 상한보다 먼저 본다. 동시 상한이 거절한 요청은 token을 쓰지 않는다(#87 결정 6의 "거절된 요청은 토큰을 쓰지 않는다"). 고장 단말이 동시에 보낸 9개가 bucket을 비우지 않으므로 같은 단말의 다음 정상 요청이 빈도 상한에 걸리지 않는다.
- 빈도 상한이 거절하면 잡은 자리를 그 자리에서 푼다. `afterCompletion`은 그 인터셉터의 `preHandle`이 끝까지 돌아 `true`를 돌려줄 때만 불리기 때문이다([HandlerInterceptor](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/HandlerInterceptor.html)).
- 자리는 `afterCompletion`에서 푼다. handler의 결과와 관계없이(예외 포함) 불린다.
- 자리를 잡았다는 표시를 요청 속성에 둔다. 표시가 있는 요청에서 `preHandle`이 다시 불리면 인증과 두 상한을 다시 하지 않고 통과한다. 비동기 handler가 생기면 ASYNC dispatch가 `preHandle`을 다시 부르는데(첫 dispatch에서는 `afterCompletion`이 불리지 않는다), 그때는 handler가 이미 일했다. 다시 인증·빈도 상한을 돌려 401·429를 내면 "429면 처리되지 않았으니 그대로 다시 보낸다"는 계약(결정 5)이 깨지고, 다시 자리를 잡으면 pod 메모리의 자리가 새서 그 단말이 재시작까지 거절만 받는다. 자리는 마지막 `afterCompletion`이 한 번 푼다. 지금 `/v1`에는 비동기 handler가 없다.
- 한계(비동기 handler가 생길 때 다시 본다): ASYNC dispatch가 오지 않는 handler(timeout 없는 `DeferredResult`)는 자리를 계속 쥐므로 비동기 handler에는 timeout이 있어야 한다. 비동기 timeout으로 자리가 풀려도 작업 스레드는 아직 커넥션을 쥐고 있을 수 있다.
- 한 인터셉터 안에서 한다. 별도 인터셉터를 두면 순서가 `addInterceptors`의 등록 순서에 기대서 두 파일을 함께 봐야 보인다(#87 결정 2와 같은 이유).
- 인증에 실패한 요청은 자리를 잡지 않는다.
- 한계: 자리는 본문을 읽기 전에 잡힌다. 업로드 도중 연결이 반쯤 열린 채 멈추면(FIN·RST 없음) Tomcat이 읽기를 포기할 때까지 그 단말의 같은 묶음은 429다. 내장 Tomcat 11.0.x의 기본 connection timeout은 60초(`org.apache.coyote.http11.Constants.DEFAULT_CONNECTION_TIMEOUT`, 클래스 파일로 확인)이고 읽기 한 번의 유휴 시간 기준이다. 사람이 손대지 않아도 풀리며, 앞단(ingress)이 본문을 끝까지 받아 넘기면 생기지 않는다(배포 확인). `server.tomcat.connection-timeout`을 줄이는 안은 모든 endpoint의 keep-alive와 요청 줄 대기도 바꾸고 근거 있는 값이 없어 두지 않았다.

**결정 5: 거절 응답은 429 `RATE_LIMITED`와 `Retry-After: 1`이다.**
- 본문은 기존 envelope이고 `error.errorCode`는 `RATE_LIMITED`, 문구도 같다. 빈도 거절인지 동시 거절인지는 응답에서 가르지 않는다. 받은 단말이 할 일(기다렸다 같은 요청을 다시 보낸다)이 같다. 어느 쪽인지는 로그와 metric에만 남긴다.
- `Retry-After`의 뜻을 "토큰 1개를 다시 얻기까지의 시간"에서 "같은 요청을 다시 보내도 되는 가장 이른 시각"으로 넓힌다. 동시 거절은 앞 요청이 언제 끝날지 모르므로 최소값 1초다(RFC 9110 §10.2.3의 delta-seconds). 대가로 부하 때 배치가 수십 초 걸리면 규약을 지키는 단말은 1~2초마다 다시 오고 거절마다 인증 조회를 치른다. k6에서 `scope="in_flight"` 증가율로 본다.
- 예외는 `platform.RateLimitedException`을 그대로 쓴다. 429는 handler 전 거절에만 쓴다는 #87 결정 5의 규칙을 지키므로, 구조 테스트의 "만드는 곳" 허용 목록에 새 limiter를 더하고 "부르는 곳은 인증 인터셉터뿐"이라는 규칙을 새 limiter의 `acquire`·`release`에 건다. `release`를 다른 곳에서 부르면 상한이 조용히 깨진다.
- 버린 것: 409 `CONCURRENT_REQUEST`. handler 안의 경합(잠금 대기 초과, unique 경합, 일부 항목이 커밋된 sync)에도 나가므로 단말이 같은 요청을 그대로 다시 보내도 되는지 알 수 없고 `Retry-After`도 없다. 새 code(`CONCURRENT_LIMITED` 등)는 단말이 할 일이 같아서 만들지 않는다.

**결정 6: 재시도 규약은 #87 결정 6 그대로다.**
- 단말은 `error.errorCode == RATE_LIMITED`이면 `Retry-After`에 0~1초 jitter를 더한 뒤 같은 요청을 그대로 보낸다. sync는 응답의 `lastAckedSequence` 다음부터 이어 보낸다.
- 정상 단말이 거절되지 않는 근거: 단말은 sync를 하나씩 보낸다(시연 단말은 `U.flushing`으로 겹쳐 보내지 않는다). 실시간 요청은 생성 → checkout → 결제 조회가 차례로 간다. backlog sync와 실시간 요청은 묶음이 다르다.
- 5초에 끊고 다시 보낸 sync: 서버에서는 앞 배치가 아직 돈다. 다시 보낸 배치는 앞 배치가 끝날 때까지 거절되고, 그 사이 앞 배치가 커밋한 항목은 다음 재전송에서 `DUPLICATE`로 돌아온 뒤 이어서 진행된다. 같은 배치가 서버에서 둘로 겹쳐 서로의 watermark 잠금을 기다리던 것도 사라진다. 같은 pod 안에서만이다. LB가 재전송을 다른 pod로 보내면(pod 3대 round-robin이면 3분의 2) 겹침이 남고, pod마다의 커넥션 상한만 지켜진다. 운영에서 재전송 겹침이 보이면 sync 경로를 단말 ID로 같은 pod에 보내는 라우팅(ingress consistent hash)이 후보다.
- backlog 회복이 막히지 않는 근거: 자리는 요청이 끝나면 성공·실패와 관계없이 풀린다. 자리가 새는 경로는 pod 종료뿐이고 그때는 메모리와 함께 사라진다. 동시 거절은 token을 쓰지 않는다.

**결정 7: 관측은 기존 counter의 scope 하나와 거절 INFO 로그다. 알림은 두지 않는다.**
- counter `inbound.rate.limited{scope}`(Prometheus 이름 `inbound_rate_limited_total`)에 `scope="in_flight"`를 더한다. label 키는 그대로이고 기동할 때 0으로 등록한다. 그래서 counter 합계가 `RATE_LIMITED` 응답 수와 계속 같다. 단말·매장 ID와 묶음은 label로 쓰지 않는다.
- 거절 로그는 limiter가 묶음별로 10초에 한 줄 INFO(`동시 요청 상한으로 거절했다 kind=sync|request terminalId=… storeId=… heldForMs=… suppressedSinceLast=… lastLoggedAt=…`)로 남긴다. `heldForMs`는 지금 자리를 쥔 요청이 잡은 뒤 지난 시간이다. 요청 timeout(트랜잭션 30초, 위 업로드 정지 60초)보다 길면 자리가 샌 것이라 고장 단말의 거절과 가를 수 있다. 줄은 묶음마다 하나라 여러 단말이 함께 거절되면(부하 때 정상 단말의 재전송) 표본 한 단말만 보인다. 줄 수를 줄이는 방식은 빈도 상한 거절 로그와 같은 것을 쓴다. 그래서 `RedisTerminalRateLimiter`의 private `Throttle`을 terminal 모듈 안에서 함께 쓰도록 꺼낸다(동작은 바뀌지 않는다). advice는 429를 남기지 않는다(지금과 같다).
- 알림을 두지 않는 이유: 부하 때 정상 단말의 재전송이 줄줄이 거절돼도 앞 배치가 끝나면 풀리고, 고장 단말의 거절은 상한이 제 일을 하는 것이다. 둘 다 사람이 손대지 않아도 풀린다. 고장 단말은 counter의 증가율과 로그의 `terminalId`로 찾는다.

**계약.** HTTP 오류 code, 응답 모양, 이벤트, 토픽, DB 스키마는 바뀌지 않는다. 429 `RATE_LIMITED`가 같은 단말의 겹친 요청에도 나가고, 그때 `Retry-After`는 1이다. REST Docs의 429 설명과 [아키텍처](../wiki/architecture.md)의 요청 흐름에 적는다.

**복구.** 거절은 handler 전이라 상태를 바꾸지 않는다. 단말은 `Retry-After` 뒤에 같은 요청을 다시 보낸다.

**범위 조정(2026-10-23, multi-review 반영).** Claude 84점, Codex(점수 없음, MEDIUM 1·LOW 2), spring-backend-specialist(P0·P1 없음, P2 1·P3 3·P4 3)였다. Gemini는 CLI가 없어 돌리지 않았다. 셋 모두 영구 차단·우회·정보 노출 경로는 찾지 못했다. 반영한 것:
- 셋 공통: ASYNC 재진입에서 인증과 빈도 상한을 다시 돌려 handler가 일한 뒤 401·429가 나갈 수 있었다. 자리를 잡은 요청은 다시 아무것도 하지 않고 통과한다(결정 4, 인수 7). #87 이후 인증 조회와 token 차감이 ASYNC dispatch에서 두 번 일어나던 것도 함께 사라진다.
- 셋 공통: "커넥션을 쓰지 않는다·2개 이하"를 인증 조회를 빼고 말했다. handler 단계로 좁혀 적는다(기대 결과, 결정 3).
- 사용자 결정 둘: 결제 시도 조회 GET은 자리를 잡지 않는다(Claude·specialist, 결정 3, 인수 15). 업로드 정지로 자리를 쥐는 시간은 설정을 바꾸지 않고 한계와 배포 확인으로 적는다(specialist P2, 결정 4).
- 하나: 자리를 쥔 시간을 거절 로그에 남겨 누수와 고장 단말을 가른다(Claude·specialist, 결정 7). `release`도 구조 규칙에 넣는다(Claude). counter 테스트가 생성 시 등록을 판별하게 고친다. 통합 테스트의 잠금 대기 감지를 잠근 세션에 막힌 잠금만 세는 기존 방식(`OrderCreateRaceIntegrationTest`의 `pg_blocking_pids`)으로 바꾸고 GET 응답 상태를 정확히 단정한다. `Retry-After` 1을 이름 있는 상수로 둔다. `HeldSlot`이 인터셉터 안에 중첩될 수 있게 구조 테스트의 양성 대조를 이름으로 좁힌다. k6 요약이 `traffic=normal` 실패율을 따로 보이게 한다. pod 사이 재전송 겹침, `Retry-After` 1의 재시도 비용, 비동기 handler의 timeout 조건, 표본 한 단말, 단일 pod 측정을 결정과 검증에 적는다. 엔티티 맵의 동시 주문 생성 문장을 고친다.
- 받지 않은 것: 자리 획득과 요청 속성 기록 사이의 실패(Codex LOW 1. 그 사이는 객체 하나 만들고 속성 하나 두는 것뿐이다). 다른 endpoint REST Docs의 429 사례(GET을 빼서 남는 쓰기 endpoint의 규약은 sync 문서와 아키텍처 wiki가 말한다). sync 판별을 표식 애너테이션으로 바꾸는 것(specialist P4, 통합 테스트가 실제 매핑으로 고정한다). SYNC 동시 거절의 `Retry-After`를 단말 timeout(5초)으로 올리는 것(k6로 재시도 비용을 본 뒤 정한다). 누수 진단용 gauge(거절 로그의 자리를 쥔 시간으로 대신한다). `TerminalOwnerControllerTest`를 메타 애너테이션으로 바꾸는 것과 아키텍처 §1 문단 나누기(동작과 무관).

## 제외
- 매장 단위 동시 상한. 매장 전체가 함께 재연결하는 정상 회복(#77 시나리오 2, 4대)도 막게 되고, 매장 총량은 빈도 상한(매장 bucket)이 맡는다.
- pod를 가로지르는 전역 동시 상한(Redis semaphore). 결정 2.
- 행 잠금 옵션 변경(`NOWAIT`, 대기 3초 축소). 결정 1.
- 인스턴스 전체의 sync 동시 실행 상한(여러 단말이 함께 재연결할 때). #77이 "구현하지 않음"으로 판정했고 다시 볼 조건은 [용량 기준](../wiki/capacity.md)에 있다.
- 시연 단말의 `Retry-After` 처리와 단말 sync timeout(5초) 규약. 지금도 전송에 실패하면 5초 뒤 다시 보낸다.
- 상한 값의 설정화. 결정 3.
- checkout을 동시에 보내는 k6 시나리오. 거절 동작은 실제 동시 요청을 보내는 통합 테스트로 확인한다.
- 알림. 결정 7.
- 배포 확인: 앞단(ingress)이 요청 본문을 끝까지 받아 앱에 넘기는지(nginx `proxy_request_buffering` 기본 on). 넘기지 않으면 업로드가 멈춘 연결이 최대 60초 그 단말의 sync 자리를 쥔다(결정 4의 한계).

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/**
- src/main/kotlin/com/resilientpos/platform/RateLimitedException.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/test/kotlin/com/resilientpos/terminal/**
- src/test/kotlin/com/resilientpos/platform/**
- src/test/kotlin/com/resilientpos/sync/internal/adapter/web/**
- load-test/lib/baseline.js
- load-test/s1-baseline.js
- load-test/README.md
- docs/wiki/architecture.md
- docs/wiki/observability.md
- docs/wiki/capacity.md
- docs/wiki/entity-map.md

## 인수 조건
1. 같은 단말의 sync가 처리 중이면 그 단말의 다음 sync는 429 `RATE_LIMITED`와 `Retry-After: 1`을 받고 Controller에 닿지 않는다. 앞 sync가 끝나면 다음 sync는 통과한다.
2. 같은 단말의 sync 밖 요청이 처리 중이면 그 단말의 다음 sync 밖 요청도 1과 같다.
3. 같은 단말의 sync가 처리 중이어도 sync 밖 요청은 통과하고, 반대도 같다.
4. 다른 단말(같은 매장 포함)의 요청은 영향을 받지 않는다.
5. 동시 상한이 거절한 요청은 빈도 token을 쓰지 않는다. 빈도 상한이 거절하면 잡은 자리를 바로 풀어서 같은 단말의 다음 요청이 동시 상한에 걸리지 않는다.
6. handler가 예외로 끝나도 자리가 풀린다.
7. 자리를 잡은 표시가 있는 요청에서 `preHandle`이 다시 불리면(ASYNC dispatch) 인증·동시 상한·빈도 상한을 다시 하지 않고 통과하고, 마지막 `afterCompletion`이 한 번 풀면 그 단말이 다시 통과한다.
8. 인증에 실패한 요청은 자리를 잡지 않고 401이다.
9. 동시 거절마다 `inbound_rate_limited_total{scope="in_flight"}`가 1 오르고, 이 시계열은 limiter를 만들 때 0으로 등록된다(첫 거절 전에 조회된다). 거절 INFO는 묶음별로 10초에 한 줄이고 kind·terminalId·storeId·자리를 쥔 시간·건수·직전 시각을 담는다. 토큰과 `Authorization` 값은 남지 않는다.
10. `RateLimitedException`은 두 limiter만 만들고, 빈도 limiter의 `acquire`와 동시 limiter의 `acquire`·`release`는 인증 인터셉터만 부른다.
11. 실제 handler mapping·인터셉터·DB 행 잠금(MockMvc, Tomcat은 거치지 않는다)에서 같은 단말의 checkout을 겹쳐 보내면, 앞 요청이 handler 안에 있는 동안 온 요청은 429다(통합 테스트).
12. REST Docs의 429 설명이 빈도 거절과 동시 거절을 함께 말한다.
13. k6 고장 단말 시나리오를 바꾸기 전과 후 빌드에서 재고 원시 수치·환경·재현 명령을 결과에 남긴다. 판정은 검증 절의 규칙을 따른다.
14. 기존 테스트가 하나도 깨지지 않는다.
15. GET 요청은 자리를 잡지 않는다. 같은 단말의 쓰기 요청이 처리 중이어도 결제 시도 조회는 동시 상한에 걸리지 않는다.

## 검증
- `./gradlew test --tests 'com.resilientpos.terminal.*'`: limiter와 인터셉터. 동시성은 실제 스레드로 handler 안에 머무는 요청을 만들어 확인한다.
- `./gradlew test`: 전체 통과. `/v1` 웹 슬라이스 테스트가 새 limiter를 받아도 깨지지 않는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), 제출 전에 `--worktree` 없이 한 번 더.
- PR 전 `resilientpos-multi-review`. 신뢰 경계의 입력 제한이라 보안 관점과 `spring-backend-specialist`를 넣는다.
- k6 고장 단말 시나리오: origin/main 빌드(63b73bc, 빈도 상한 있음)와 이 브랜치 빌드를 같은 날 같은 compose 스택에서 잰다. 빌드마다 바탕만(`k6 run -e RATE=70 load-test/s1-baseline.js`)과 바탕에 고장 단말을 더한 실행(`-e TERMINAL_BURST=10 -e BURST_EVERY=5s`)을 2분씩 돌린다. `BURST_EVERY`는 이 PR이 `lib/baseline.js`에 더하는 묶음 간격이다. main에는 빈도 상한이 있어, 묶음을 초마다 보내면 bucket이 비어 고장 단말의 동시 요청이 초당 2개(refill)로 줄어 이슈의 장면(가득 찬 bucket으로 한꺼번에)이 재현되지 않는다. 5초는 빈 bucket이 가득 차는 시간(capacity 10 ÷ refill 2)이다. 측정 전에 `docker ps`와 다른 세션 상태를 확인하고, 다른 worktree가 띄운 스택은 내리지 않는다.
- 읽는 값: `max_over_time(hikaricp_connections_pending[1m])`와 `active`의 최대, `traffic=normal` sync의 p95/p99와 실패율(k6. 고장 단말의 기대된 429가 섞이지 않게 `s1-baseline.js`의 thresholds에 `traffic:normal`과 `traffic:burst` submetric을 더한다), 고장 단말 요청의 상태별 수(200·429), `inbound_rate_limited_total` scope별 증가. pending이 판정을 넘으면 인증 조회(거절된 요청도 치른다) 몫인지 먼저 가른다. 측정은 앱 한 대(단일 pod)다.
- 판정 규칙(측정 전에 정한다): 바꾼 뒤 고장 단말 실행의 pending 최대와 active 최대가 같은 빌드의 바탕만 실행보다 각각 2 이하로 크고, `traffic=normal` sync p95가 바탕만 실행의 1.2배 안이며 실패율이 0.1% 아래면 통과다. 바꾸기 전 빌드에서 고장 단말 실행이 이 차이를 넘지 않으면(문제가 재현되지 않음) 그 사실과 수치를 결과에 적고 결론을 다시 묻는다.

## Wiki 영향
- [아키텍처](../wiki/architecture.md): 요청 흐름 1번(인증과 빈도 상한)에 동시 상한 단계와 순서, 429의 `Retry-After` 뜻을 넣는다. §6.4 구조 테스트 표의 rate limit 행에 새 limiter를 더한다.
- [관측성](../wiki/observability.md): 단말 빈도 상한 항목에 `scope="in_flight"`와 동시 거절 INFO 로그, 알림을 두지 않는 이유를 더한다.
- [용량 기준](../wiki/capacity.md): "단말·매장 rate limit 권장값"의 상한 조건 문단을 이 PR의 결과(한 단말이 pod 풀에서 쥐는 커넥션 최대 2개, k6 전후 수치)로 바꾼다.
- [엔티티 맵](../wiki/entity-map.md) §4.1: 같은 `clientOrderId` 동시 주문 생성이 같은 pod·같은 단말이면 뒤 요청이 429라는 것을 더한다.
- README: 무영향. Redis 쓰임이 바뀌지 않는다(이 상한은 pod 메모리다).
- [도메인 모델](../wiki/domain-model.md): 무영향. aggregate·스키마·용어가 바뀌지 않는다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트): 머지할 때 단말 경로 보호에 "단말별 동시 요청 상한(pod 메모리, sync 1·그 밖 1)"을 더한다. 볼트 파일이라 이 PR diff에는 없다.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트): 무영향. Kafka·Modulith 작업이 아니다.
- 요구사항 변화: 같은 단말의 겹친 쓰기 요청은 pod마다 sync 1개와 그 밖 1개까지만 처리되고(결제 시도 조회는 묶지 않는다), 넘으면 429 `RATE_LIMITED`와 `Retry-After: 1`이다. 단말은 #87의 규약대로 같은 요청을 다시 보낸다.

## 분할 계획
- 이 PR은 단말별 동시 요청 상한 하나의 개발 컨텍스트다. 인스턴스 전체의 sync 동시 실행 상한, 단말 sync timeout 규약, 행 잠금 대기 시간은 따로 둔다(제외).
- 겹침 확인(2026-10-23, origin/main `63b73bc`): 열린 PR은 #94(draft, `refactor/modulith-2-1-2-resubmit`)뿐이고 그 경로(`build.gradle.kts`, `EventPublicationResubmitter`와 그 테스트, `domain-model.md`, 자기 spec)는 이 PR과 겹치지 않는다. 진행 중이지만 PR이 없는 브랜치 중 `feature/slo-alert-baseline`(#103)이 `docs/wiki/observability.md`를 고쳐 이 PR과 겹친다(같은 파일의 다른 항목일 가능성이 크다). `alerts.yaml`·`alerts.test.yaml`·`runbook.md`는 이 PR이 건드리지 않는다. `fix/boot-4-1-2`(`build.gradle.kts`)는 겹치지 않는다. push·PR 생성·머지 직전에 다시 확인하고 먼저 머지된 쪽에 맞춰 rebase한다.
- 공용 헬퍼: main과 #94에 동시 요청 상한이나 in-flight 계수기가 없다. 로그 줄 수를 줄이는 헬퍼는 `RedisTerminalRateLimiter`의 private `Throttle` 하나뿐이라, 새로 만들지 않고 그것을 같은 모듈에서 함께 쓰도록 꺼낸다.

## TDD 검증 기록
limiter와 인터셉터의 동작 변경에 적용한다. 명령은 모두 `./gradlew test --tests 'com.resilientpos.terminal.*'`이다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `TerminalInFlightLimiterTest`: 같은 단말의 같은 묶음은 처리 중이면 다음 요청이 retryAfter 1초로 거절되고 푼 뒤에는 통과한다(인수 1·2) | `--tests '…web.TerminalInFlightLimiterTest'`. `acquire`·`release`가 빈 본문인 선언만 두고 실행해 `assertFailsWith`가 실패(예외가 나지 않음) | 같은 명령, 통과 | `--tests 'com.resilientpos.terminal.*'` 통과(로그 간격 헬퍼를 꺼낸 뒤 `RedisTerminalRateLimiter*Test` 포함) |
| 같은 단말이어도 묶음이 다르면 서로 막지 않는다(인수 3) | 위 RED에서 함께 실패(두 묶음을 연달아 잡은 뒤 같은 묶음의 거절 기대가 빗나감) | 통과 | 위와 같음 |
| 다른 단말은 같은 매장이어도 영향을 받지 않는다(인수 4) | 빈 구현도 통과한다. 거절 키를 단말로 좁히는지 보는 대조로 남긴다 | 통과 | 위와 같음 |
| `TerminalAuthenticationInterceptorTest`: 성공 요청은 동시 상한 빈도 상한 Controller 순서로 지나고 끝나면 REQUEST 자리를 한 번 푼다, 단말 sync 경로 패턴은 SYNC 묶음으로 자리를 잡고 푼다(인수 1·2) | `--tests '…web.TerminalAuthenticationInterceptorTest'`(인터셉터 연결 전). 새 동작 7건이 모두 실패했다. 6건은 `WantedButNotInvoked`(`inFlight.acquire`가 불리지 않음)이고 1건은 아래 줄이다 | 같은 명령, 11건 통과 | `--tests 'com.resilientpos.terminal.*'` 통과, `./gradlew test` 전체 919건 통과 |
| 동시 상한이 거절하면 429와 Retry-After 1이고 Controller와 빈도 limiter와 release를 부르지 않는다, 빈도 상한이 거절하면 잡은 자리를 푼다(인수 5) | 위 RED. 동시 거절 테스트는 429 대신 200이었다 | 통과 | 위와 같음 |
| handler가 예외를 던져도 자리를 한 번 푼다, 비동기 dispatch는 자리를 다시 잡지 않고 마지막에 한 번 푼다, 비동기 dispatch에서 빈도 상한이 던져도 자리를 푼다(인수 6·7) | 위 RED(`WantedButNotInvoked`) | 통과 | 위와 같음 |
| 형식이 토큰과 다른 값·모르는 토큰이면 limiter를 부르지 않는다(인수 8) | 기존 두 테스트에 `verifyNoInteractions(inFlight)`를 더했다. 처음부터 통과하는 characterization이다 | 통과 | 위와 같음 |
| `TerminalInFlightLimiterTest`: acquire는 인증 인터셉터만 부른다(인수 10의 부르는 곳) | 구현 뒤에 더해 처음부터 통과했다. 양성 대조가 있어 인터셉터가 부르지 않으면 실패하는 규칙이다(실패 상태를 직접 보지는 않았다) | 통과 | 위와 같음 |
| 거절마다 in_flight scope counter가 1 오르고 처음부터 0으로 있다, 같은 묶음의 거절 로그는 10초에 한 줄이고 건수와 직전 시각을 다음 줄에 적는다, 묶음이 다르면 거절 로그도 따로 남긴다(인수 9) | 위 RED에서 함께 실패(counter 0, 거절 로그 0줄) | 통과 | 위와 같음 |
| `RateLimitedExceptionTest`: RateLimitedException은 handler 전에 판정하는 빈도·동시 상한 limiter만 만든다(인수 10의 만드는 곳) | limiter를 더한 뒤 허용 목록을 고치기 전 `--tests 'com.resilientpos.platform.RateLimitedExceptionTest'`가 실패(3개 중 1개, 새 limiter의 생성 호출) | 허용 목록에 새 limiter와 그 양성 대조를 더해 통과 | 위와 같음 |
| `TerminalInFlightIntegrationTest`: 같은 단말의 checkout이 주문 행 잠금을 기다리는 동안 온 checkout은 429 RATE_LIMITED이고 잠금이 풀리면 앞 요청은 200이며 다음 checkout은 통과한다, 같은 단말의 sync가 watermark 행 잠금을 기다리는 동안 온 sync는 429이고 sync 밖 요청은 429가 아니며 잠금이 풀리면 앞 sync는 200이다(인수 1·3·11) | 구현 뒤에 써서 처음부터 통과했다(`--tests '…web.TerminalInFlightIntegrationTest'`, 2건). 판별력은 운영 코드를 잠깐 바꿔 확인하고 Edit로 되돌렸다. 인터셉터의 sync pattern을 틀리게 하면 sync 장면이 실패했다(sync 밖 요청이 429). `inFlight.acquire` 호출을 지우면 두 장면 모두 실패했다(429 대신 409. 겹친 요청이 3초 동안 잠금을 기다리다 대기 초과로 끝났다) | 같은 명령, 2건 통과 | `--tests 'com.resilientpos.terminal.*'` 123건 통과 |

## 결과
개발 전 작성. 개발 후 실행 결과, k6 전후 수치, 변경 이유를 추가한다.
