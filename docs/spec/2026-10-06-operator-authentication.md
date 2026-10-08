# PR spec: 점주 계정과 매장, 점주의 단말 관리 HTTP 입구를 연다

## 목적
단말 인증([terminal-authentication](2026-10-05-terminal-authentication.md))은 들어왔지만, 단말을 등록·회전·비활성화하는 입구가 `TerminalManagement` Facade뿐이다. 배포 환경에서는 단말을 등록할 방법이 없어서 API를 외부에 노출하지 못한다. 단말을 관리하는 사람은 점주(SaaS 고객)이고 자기 매장의 단말만 다룬다. 그래서 점주 계정과 매장을 들이고, 그 위에 단말 관리 HTTP API를 연다(이슈 #86).

기대 결과:
- 점주는 이메일과 비밀번호로 가입하고 로그인한다. 로그인 뒤 요청은 서버 세션 쿠키로 인증한다.
- 점주는 매장을 만들고 자기 매장 목록을 본다.
- 점주는 자기 매장에 단말을 등록하고, credential을 회전하고, 비활성화하고, 단말 목록을 본다. 평문 토큰은 등록·회전 응답에 한 번만 실린다. 남의 매장과 그 단말은 없는 것과 같다.
- 단말에 매장 안에서 유일한 `TerminalCode`가 생기고 기존 행은 채워진다.

## 범위
소유 모듈은 새 `owner`(점주 계정, 가입, 로그인 검증), `store`(매장 aggregate, 소유 확인), `terminal`(단말 코드, 관리 API, 목록 조회), `platform`(점주 Security 체인, 401·403 응답)이다. 참고 문서의 근거는 시스템 설계의 "운영 API는 별도 role/scope 및 audit를 요구한다", PRD PR-05와 청사진의 "operator action에는 principal … 을 남긴다", 청사진 §2 `terminal` 행의 "매장 안에서 코드 유일"이다.

**결정 1: 점주는 앱 자체 계정이다.** 점주는 우리 조직의 직원이 아니라 SaaS 고객이므로 신원(가입·로그인·비밀번호)을 앱이 관리한다(Square·Toast 방식). OIDC IdP(Keycloak)에 맡기는 안은 버렸다. 고객 계정에 IdP를 두면 IdP 운영과 IdP·앱 사이의 계정 동기화가 따라오는데, 앱이 이미 매장 소속을 정본으로 가져야 해서 얻는 것이 없다.
- 계정과 매장의 소속 관계는 앱 DB가 정본이다. 토큰이나 세션에 매장 목록을 담지 않고 요청마다 `t_store.owner_id`로 확인한다.
- 점주는 자기 매장만 다룬다. 매장을 넘나드는 작업(DLT 재투입 등)은 점주가 아닌 플랫폼 운영자의 몫이고, 그 주체는 이 PR에 없다(제외).
- 용어: 점주는 `owner`, 경로는 `/owner/v1/**`다. `operator`와 `/admin`은 플랫폼 운영자에 남겨 둔다.
- [terminal-authentication](2026-10-05-terminal-authentication.md) 결정 1("Spring Security를 들이지 않는다. 두 번째 주체가 생기면 다시 본다")을 점주 주체에 한해 다시 정한다. 점주가 두 번째 주체다.

**결정 2: 가입은 계정만 만들고, 매장은 로그인한 점주가 따로 만든다.**
- `POST /owner/v1/signup` 본문 `{email, password}` → `201 {ownerId, email}`. 로그인시키지 않는다.
- 이메일은 앞뒤 공백을 지우고 소문자로 바꿔 저장한다. 형식은 `@` 앞뒤에 공백 없는 문자가 있고 254자 이하다. 틀리면 `400 INVALID_EMAIL`이다. unique `ux_owner_email`이 마지막 방어선이고 사전 조회는 하지 않는다. 위반은 adapter가 제약 이름으로 가려 `409 OWNER_EMAIL_DUPLICATED`로 바꾼다. 가입 단계에서 계정 존재가 드러나는 것은 받아들인다. 이메일 인증이 없어 막을 방법이 없다.
- 이메일 소유는 확인하지 않는다. 남의 이메일로 먼저 가입하는 일을 막지 못한다. 이메일 인증은 메일 인프라와 함께 후속 PR로 뺐다(제외).
- `POST /owner/v1/stores` 본문 `{name}` → `201 {storeId, name}`. 매장 이름은 앞뒤 공백을 지우고 1~100자다. 틀리면 `400 INVALID_STORE_NAME`이다. 한 점주가 여러 매장을 가질 수 있고 이름이 겹쳐도 된다.
- `GET /owner/v1/stores` → `200 [{storeId, name}]`, 내 매장만 생성 순이다. 페이지네이션은 두지 않는다. 한 점주의 매장 수는 한정적이다.
- 소속은 `t_store.owner_id` 컬럼 하나다. 매장 하나를 여러 계정이 함께 관리하는 기능이 없어 소속 표는 두지 않는다. 매장의 소유자를 바꾸는 명령도 없다.

**결정 3: 로그인 뒤 인증은 서버 세션 쿠키다. 세션은 Spring Session Redis에 둔다.**
- `POST /owner/v1/login` 본문 `{email, password}` → `200 {ownerId, email}`와 세션 쿠키다. 로그인할 때 세션 ID를 새로 만들어 세션 고정을 막는다. 세션에는 `SecurityContext`(점주 ID)만 둔다.
- `POST /owner/v1/logout` → `204`. 세션을 지운다.
- 쿠키는 `__Host-SESSION`이고 `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, Domain 없음이다. `__Host-` 접두어는 형제 서브도메인이 같은 이름의 쿠키를 심지 못하게 한다. 유휴 만료는 30분(Spring Session 기본)이고 절대 수명은 없다(제외). 세션은 Redis에 있어 인스턴스가 여러 대여도 같다. 저장소는 색인 없는 기본 저장소(`RedisSessionRepository`)다. 점주별 세션 목록이 필요한 기능(비밀번호를 바꾸면 다른 세션 폐기)이 없어서다.
- 비용: 세션 하나는 Redis hash 하나(JSON 1~2KB, 추정)이고, 유휴 만료로 지금 로그인해 있는 점주 수만큼만 쌓인다. 요청마다 Redis 왕복이 1~2회 생긴다. Spring Session 필터는 모든 요청에 걸리지만 세션을 지연 로딩한다. MVC의 기본 `FlashMapManager`는 요청마다 세션을 읽으므로 아무것도 하지 않는 구현으로 바꾼다(결정 12). 그래서 세션 쿠키가 실려 와도 단말 경로(`/v1/**`)는 Redis에 닿지 않는다. 실측은 부하테스트에서 한다(결과의 한계).
- Redis 장애 때 점주 요청은 세션을 읽거나 쓰지 못해 실패한다(fail-closed). 응답은 envelope의 `503`이다(결정 12). 단말 인증은 영향이 없다. health는 Redis를 보지 않는다. 보게 하면 Redis 장애 때 readiness가 빠져 단말 핫패스까지 막힌다. 감지는 `/owner/**` 5xx와 Redis 모니터링으로 한다([관측성](../wiki/observability.md)).
- 세션 속성은 JSON으로 직렬화한다(결정 13). 운영 Redis의 인증·TLS는 그래도 외부 노출의 전제다. Redis에 쓸 수 있는 주체는 세션을 위조할 수 있기 때문이다.
- 버린 것: 불투명 Bearer 토큰은 요청마다 Postgres를 조회하고 콘솔 JS가 토큰을 들고 있어 XSS에 노출된다. 자체 발급 JWT는 만료 전 폐기가 안 되고 서명 키 관리와 refresh 토큰 표가 따라온다.

**결정 4: CSRF는 SPA 방식이다.** 쿠키로 인증하므로 CSRF를 막아야 한다. Spring Security 7의 `csrf.spa()`를 쓰고, 토큰 쿠키는 `__Host-XSRF-TOKEN`(`Secure`, `Path=/`, JavaScript가 읽도록 HttpOnly 아님), 헤더는 `X-XSRF-TOKEN`이다. 체인과 로그인 Controller는 같은 저장소 인스턴스(`platform.OwnerCsrfTokens`)를 쓴다. 이중 제출 토큰은 세션에 묶이지 않으므로 쿠키를 심을 수 없게 하는 `__Host-` 접두어가 방어의 일부다. 가입·로그인·로그아웃을 포함한 `/owner/**`의 모든 상태 변경 요청에 토큰이 필요하다. 로그인 CSRF도 막기 위해서다. 클라이언트가 첫 토큰을 받는 `GET /owner/v1/csrf`(`204`, 쿠키를 심는다)를 둔다. 토큰이 없거나 틀리면 `403 ACCESS_DENIED`다. CSRF 필터가 인증보다 먼저라, 세션 없이 토큰도 없는 상태 변경 요청은 401이 아니라 403이다. 거절 로그에는 코드만 남긴다. `InvalidCsrfTokenException`의 메시지가 제출 토큰을 담기 때문이다.

**결정 5: 비밀번호.**
- 해시는 Spring Security `PasswordEncoderFactories.createDelegatingPasswordEncoder()`다(기본 bcrypt). 저장값에 `{bcrypt}` 접두어가 있어 나중에 다른 알고리즘으로 옮길 수 있다.
- 정책은 15자 이상, UTF-8 72바이트 이하다. 15자는 MFA 없는 단일 요소 비밀번호에 대한 NIST SP 800-63B-4의 최소값이다. 72바이트는 bcrypt의 입력 한계이고, Spring Security는 이를 넘는 입력을 거절한다. 조합 규칙(대문자·특수문자)은 두지 않는다. 틀리면 `400 INVALID_PASSWORD`다. 유출 비밀번호 목록 대조는 외부 API라 두지 않는다(제외).
- 평문 비밀번호는 로그, 예외 메시지, 요청 DTO의 `toString` 어디에도 남지 않는다.

**결정 6: 로그인 실패 제한은 Redis 카운터다.**
- 키는 정규화한 이메일의 SHA-256이다. 형식이 맞는 이메일의 로그인 시도는 비교 전에 먼저 센다(INCR). 센 값이 5를 넘으면 비밀번호를 비교하지 않고 거절한다. 로그인에 성공하면 키를 지운다. 창은 첫 시도부터 15분이고, 창이 끝나면 저절로 풀린다.
- 증가와 TTL은 Lua 스크립트 하나로 한다(INCR, TTL이 없으면 PEXPIRE). 동시에 들어온 시도도 각자 하나씩 세므로 창 안의 비교는 정확히 5번이다. 따로 보내면 EXPIRE만 빠진 키가 TTL 없이 남아 영구 잠금이 될 수 있고, 스크립트는 어떤 경로로 생긴 TTL 없는 키도 다음 시도에서 TTL을 얻게 한다.
- 응답은 원인(없는 계정, 틀린 비밀번호, 잠김)과 무관하게 같은 `401 UNAUTHENTICATED`다. 계정 존재를 알리지 않으려고 없는 계정에도 카운터를 올리고, 더미 해시와 비교해 응답 시간을 맞춘다.
- 카운터가 Redis에 닿지 못하면 비밀번호를 비교하지 않고 `503 SERVICE_UNAVAILABLE`로 답한다(fail-closed, 2026-10-08 범위 조정). 원래는 분산 락처럼 fail-open이었다. 그런데 같은 Redis에 세션을 쓰지 못하면 로그인은 어차피 성공할 수 없다. 게다가 틀린 비밀번호는 401, 맞는 비밀번호는 세션 저장 실패로 503이 되어, 제한 없는 비밀번호 판별 신호가 생긴다(🔴 점검). 분산 락은 fail-open 그대로다. 잠금 상태는 업무 사실이 아니라 DB에 두지 않는다(스키마 변경 검토 질문 1).
- 공격자가 점주 계정을 15분씩 막을 수 있다는 것은 받아들인다.

**결정 7: Security 체인은 `/owner/**`만 맡는다. 단말 인증은 지금의 인터셉터로 둔다.**
- `platform`의 `SecurityFilterChain` 하나가 `securityMatcher("/owner/**")`로 점주 경로만 잡는다. 가입·로그인·CSRF 토큰은 누구나, 나머지는 인증된 점주만 부른다. 다른 경로는 어떤 체인과도 맞지 않아 체인의 필터를 지나지 않는다. `FilterChainProxy`의 `StrictHttpFirewall`은 체인을 고르기 전에 모든 요청에 걸려서 경로에 `;`·인코딩된 `/`·`//`가 있으면 거절한다. 단말이 보내는 경로는 UUID와 고정 문자열이라 걸리지 않는다. `/v1/**`(단말 인터셉터), `/actuator/**`, 시연 정적 자원은 지금과 같게 동작한다.
- 로그인은 Spring Security의 form login을 쓰지 않는다. form login은 form 인코딩과 redirect를 전제하는데, 이 API는 JSON과 envelope로 답한다. 로그인 Controller가 `OwnerManagement`로 자격을 확인하고, `SecurityContext`를 세션에 저장한다.
- 인증 수단이 둘(단말 인터셉터, 점주 세션)로 나뉘는 대가를 치른다. 단말 인증을 Security로 옮기면 #81의 401 계약을 Security 쪽에서 다시 만들고 슬라이스 테스트 전반을 고쳐야 해서 이 PR에 넣지 않는다(제외).

**결정 8: 오류 응답은 기존 envelope를 쓴다.**
- `AuthenticationEntryPoint`와 `AccessDeniedHandler`는 응답을 직접 쓰지 않고 `HandlerExceptionResolver`에 예외를 넘겨 `ApiControllerAdvice`가 envelope와 로그를 맡게 한다.
- 세션이 없거나 만료되면 `401 UNAUTHENTICATED`다. 쿠키 인증이라 `WWW-Authenticate`는 붙이지 않는다(단말의 401은 지금처럼 `Bearer`를 붙인다).
- CSRF 토큰 오류는 `403 ACCESS_DENIED`다. 업무 규칙이 아니라 HTTP 공통 오류라 `ErrorType`에 더한다.
- 도메인 예외 중 `kind`가 `NOT_FOUND`인 것(`STORE_NOT_FOUND`·`TERMINAL_NOT_FOUND` 등)은 advice가 INFO로 남긴다. 없는 경로의 `ErrorType.NOT_FOUND`(INFO)와 수준을 맞춘다. 다른 매장 ID를 훑는 요청이 WARN으로 쌓여 경보 소음이 되지 않게 하려는 것이다. 그 밖의 도메인 예외는 지금처럼 WARN이다(2026-10-08 범위 조정, 🔴 점검 P4). 모든 모듈의 도메인 404에 걸린다.

**결정 9: 단말 관리 API는 매장 경로 아래에 둔다.** Controller는 `terminal.internal.adapter.web`에 둔다. 경로가 어느 매장의 무엇을 다루는지 보여 준다.
- 모든 endpoint는 먼저 `StoreManagement`의 소유 확인을 부른다. 매장이 없거나 남의 매장이면 `404 STORE_NOT_FOUND`다. 다른 매장 ID의 존재를 알리지 않는다. 매장 소유자는 바뀌지 않으므로 확인과 단말 변경 사이의 경합은 없다.
- `POST /owner/v1/stores/{storeId}/terminals` 본문 `{terminalCode}` → `201 {terminalId, terminalCode, token}`.
- `POST /owner/v1/stores/{storeId}/terminals/{terminalId}/credential/rotate` → `200 {token}`.
- `POST /owner/v1/stores/{storeId}/terminals/{terminalId}/deactivate` → `200 {terminalId, status}`. 이미 비활성이면 같은 응답이다(멱등).
- 회전과 비활성화는 커맨드에 `storeId`를 담는다. Facade는 잠근 단말의 매장이 다르면 `404 TERMINAL_NOT_FOUND`로 거절한다.
- `GET /owner/v1/stores/{storeId}/terminals` → `200 [{terminalId, terminalCode, status}]`, `terminalCode` 오름차순. 페이지네이션은 두지 않는다. 쓰기 Facade와 나눈 `TerminalQueryFacade`와 `persistence/query`의 `TerminalQueries`(Kotlin JDSL)로 읽는다([payment-status-query](2026-10-06-payment-status-query.md)의 읽기 규약).
- 평문 토큰은 등록·회전 응답에만 있다. 응답 DTO의 `toString`은 토큰을 가린다.
- 기존 오류는 그대로다. 비활성 단말의 회전은 `409 TERMINAL_INACTIVE`다.
- 회전 유예는 두지 않는다([terminal-authentication](2026-10-05-terminal-authentication.md) 결정 3 유지). 옛 토큰은 회전 커밋부터 401이다.
- 비활성 단말의 미전송 큐는 받지 않는다. 비활성화는 분실·도난 대응이고 "비활성 단말은 인증 불가"가 불변식이다. 큐를 살려야 하는 교체라면 회전을 쓴다고 REST Docs 설명과 wiki에 적는다.
- `t_store` 행이 없는 매장에 속한 기존 단말(배포 환경에는 없다)은 인증은 되지만 어느 점주도 관리할 수 없다.

**결정 10: `TerminalCode`.**
- 점주가 등록할 때 준다. 형식은 영대문자·숫자·하이픈 1~32자(`^[A-Z0-9-]{1,32}$`)이고, 소문자를 대문자로 바꾸지 않고 `400 INVALID_TERMINAL_CODE`로 거절한다. 요청과 응답의 값이 같게 하려는 것이다. 값 객체 `TerminalCode`가 형식을 검사한다.
- 매장 안에서 유일하다. 마지막 방어선은 unique `(store_id, terminal_code)`이고 사전 조회는 하지 않는다. 위반은 persistence adapter가 SQLState `23505`와 제약 이름으로 가려 `409 TERMINAL_CODE_DUPLICATED`(`DomainException`, `CONFLICT`)로 바꾼다. 동시 등록도 같은 경로를 탄다.
- 코드는 바뀌지 않는다. 바꾸는 명령은 두지 않는다.
- 기존 행은 `LEGACY-` + 매장별 `created_at, id` 순번으로 채운다(스키마 변경 검토).

**결정 11: 감사 로그.** Facade가 커밋한 뒤 Controller가 INFO 한 줄을 남긴다. 항목은 `ownerId`, `action`(`STORE_CREATED`·`TERMINAL_REGISTERED`·`TERMINAL_CREDENTIAL_ROTATED`·`TERMINAL_DEACTIVATED`), 대상 ID다. 매장 생성이면 `storeId`, 단말이면 `storeId`·`terminalId`이고, 등록이면 `terminalCode`, 비활성화면 전후 `status`를 더한다. 토큰, 비밀번호, 세션 ID, 이메일은 남기지 않는다. 가입은 `OWNER_SIGNED_UP`(`ownerId`)으로 남긴다. 로그인 성공은 `OWNER_LOGGED_IN`(`ownerId`) INFO이고, 실패는 advice의 기존 401 WARN만 남긴다. 사유·증거를 담는 감사 표는 그것이 필요한 PR이 만든다.

**결정 12: 세션 저장소(Redis) 장애는 envelope의 `503 SERVICE_UNAVAILABLE`로 답한다.** (2026-10-07 범위 조정)
- 문제: Spring Session 필터는 MVC 밖의 서블릿 필터라, Redis 오류가 `ApiControllerAdvice`를 거치지 않고 Tomcat 기본 HTML 500으로 나간다(실측). 클라이언트가 envelope의 `errorCode`로 분기하지 못하고, 서버 버그(500)와 일시 장애가 구분되지 않는다.
- 수단: `platform`의 서블릿 필터 하나를 `/owner/*`에만 걸고 Spring Session 필터보다 앞에 둔다. 체인 안에서 Redis 오류(`DataAccessException`)가 올라오고 응답이 아직 커밋되지 않았으면, 응답을 비우고 `handlerExceptionResolver`로 넘긴다. 그러면 `ApiControllerAdvice`가 `503 SERVICE_UNAVAILABLE`(`ErrorType`, ERROR 로그)을 쓴다. 세션 읽기(요청 처리 중)와 세션 저장(응답 커밋 때) 실패를 모두 덮는다. 응답이 이미 커밋됐으면 바꿀 수 없으므로 다시 던진다.
- 단말 경로와 다른 경로는 이 필터를 지나지 않는다. 분산 락은 fail-open이라 Redis 오류를 adapter에서 삼킨다.
- MVC 안에서 세션에 닿는 곳은 둘이다. `DispatcherServlet`은 요청마다 `FlashMapManager`로 세션을 읽는다(spring-webmvc 7.0.9 `doService`). 이 오류는 `ServletException`으로 감싸져 필터의 `DataAccessException` catch를 지난다. 이 API는 redirect와 flash 속성을 쓰지 않으므로 아무것도 하지 않는 `FlashMapManager`(빈 이름 `flashMapManager`)로 이 읽기를 없앤다. 같은 서블릿이 요청 끝마다 `ServletRequestHandledEvent`를 만들며 세션 ID를 읽으므로(`publishRequestHandledEvent`), 구독자가 없는 그 이벤트도 끈다(`spring.mvc.publish-request-handled-events: false`). 다른 하나는 로그인 Controller의 세션 조작(세션 ID 교체, 컨텍스트 저장)이다. 여기서 난 Redis 오류는 `SessionStoreUnavailableException`으로 바꿔 던져 503이 되게 한다. 로그인 실패 카운터의 Redis 오류도 같은 예외다(결정 6).
- 503은 변경이 커밋되지 않았다는 뜻이 아니다. 인증 경로의 세션 저장은 Facade 커밋 뒤 응답을 커밋할 때 일어난다. 그래서 등록·회전이 커밋된 뒤에도 503이 나갈 수 있다. 등록·회전 뒤 503을 받으면 목록으로 확인하고, 토큰이 없으면 회전한다. 이 내용을 REST Docs 설명에도 적는다.
- 한계: 로그인 감사 로그(`OWNER_LOGGED_IN`)는 세션 저장보다 먼저 남으므로, 저장이 실패해 503이 나가도 로그는 남는다.

**결정 13: 세션 속성은 JSON으로 직렬화한다.** (2026-10-07 범위 조정)
- 문제: Spring Session 기본 JDK 직렬화에는 두 가지 문제가 있다. Spring Security 버전을 올리면 `serialVersionUID`가 바뀌어 기존 세션을 읽지 못한다. 또 Redis에 쓸 수 있는 주체가 역직렬화 gadget으로 코드를 실행할 수 있다. 프로젝트 규칙도 `java.io.Serializable` 직렬화를 쓰지 않는다.
- 수단: Spring Session의 Redis serializer 빈을 Spring Data Redis의 `GenericJacksonJsonRedisSerializer`(Jackson 3)로 바꾼다. 다형성 타입은 Spring Security의 `SecurityJacksonModules`가 허용하는 타입만 받는다(allowlist). 세션 principal(점주 UUID)이 허용 목록에 없으면 가장 작은 변경으로 맞춘다. 예를 들면 principal을 문자열로 두거나, 그 타입 하나만 허용 목록에 더한다.
- 기존 세션: 배포된 점주 세션이 없어 형식 전환 비용이 없다. 전환 뒤 JDK 형식으로 남은 세션을 읽지 못하면 그 요청은 다시 로그인하게 한다(503이 아니라 401).

계약: 공개 타입이 생긴다.
- `owner`: `OwnerId`, `OwnerManagement`, `OwnerCommand`.
- `store`: `StoreManagement`(생성·내 매장 목록·소유 확인), `StoreCommand`, `StoreView`.
- `terminal`: `TerminalCode`, `TerminalQuery`, `TerminalView`, `TerminalQueryFacade`.

기존 계약은 이렇게 바뀐다.
- `TerminalStatus`는 `TerminalView`가 담아야 해서 `terminal.internal.domain`에서 모듈 루트로 옮긴다.
- `TerminalCommand.Register`가 `terminalCode`를 받고 결과에 담는다.
- `RotateCredential`과 `Deactivate`가 `storeId`를 받는다.
- `deactivate`는 전후 상태를 담은 `TerminalCommand.Deactivate.Result`를 돌려준다.

새 오류 code는 `ACCESS_DENIED`·`SERVICE_UNAVAILABLE`(`ErrorType`), `INVALID_EMAIL`, `INVALID_PASSWORD`, `OWNER_EMAIL_DUPLICATED`, `INVALID_STORE_NAME`, `STORE_NOT_FOUND`, `INVALID_TERMINAL_CODE`, `TERMINAL_CODE_DUPLICATED`다. 이벤트와 `event_publication`은 바뀌지 않는다. 모듈 의존은 `terminal → store`, `terminal → owner`(`OwnerId`), `store → owner`다.

불변식:
- 점주 경로는 유효한 세션 없이 부를 수 없다. 점주는 자기 매장과 그 단말만 다룬다.
- 이메일은 계정 하나에만 쓰인다. 매장 안에서 단말 코드는 하나다.
- 평문 비밀번호와 평문 단말 토큰은 저장하지 않고, 토큰은 응답에 한 번만 나간다.
- 그 밖의 단말 불변식(유효 credential 하나, 비활성 단말 인증 불가)은 기존 행 잠금과 partial unique가 그대로 지킨다.

복구:
- 등록 응답을 잃으면 그 단말은 토큰을 모르므로, 목록에서 `terminalId`를 찾아 회전한다. 회전 응답을 잃으면 다시 회전한다. 비활성화는 멱등이다.
- 코드 중복·이메일 중복 409는 아무것도 바꾸지 않는다.
- 매장 생성 응답을 잃으면 목록에서 찾는다. 다시 만들면 매장이 둘 생기는데, 이름 중복을 허용하므로 그것을 막지 않는다.
- 비밀번호를 잊은 점주는 스스로 복구할 수 없다(제외).

관측성: 감사 INFO 로그(결정 11)와 advice의 기존 WARN 로그(400·401·403·404·409)를 쓴다. 새 metric은 없다.

## 제외
- 플랫폼 운영자 주체와 그 위의 DLT 재투입 API([kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md) 제외 절). 결정 1. #91
- 이메일 인증, 잊은 비밀번호 재설정, 로그인 중 비밀번호 변경(과 그때의 다른 세션 폐기). 모두 메일 인프라나 색인 세션 저장소가 필요해 한 후속 PR로 묶는다. 결정 2·3. #92
- MFA(TOTP). #93
- 유출 비밀번호 목록 대조, 가입·로그인의 IP별 rate limit. 결정 5·6. rate limit이 없으면 무작위 이메일 로그인과 가입이 요청마다 bcrypt를 돌려 CPU를 고갈시킬 수 있다. 외부 노출 전에 #87과 함께 정한다.
- 세션 절대 수명, 비밀번호 Unicode 정규화(NFKC). 결정 3·5.
- 매장 하나를 여러 계정이 관리하는 기능(직원 초대·역할), 매장 소유자 변경, 매장 이름 변경·삭제.
- 단말 인증의 Spring Security 이관. 결정 7.
- 감사 표. 결정 11.
- 회전 유예, 재활성화, 비활성 단말의 큐 드레인, 단말 코드 변경 명령. 결정 9·10.
- 관리 콘솔 화면과, 시연 화면(`static/terminal`)이 관리 API를 부르는 것.
- 운영자 재결제 입구([payment-status-query](2026-10-06-payment-status-query.md) 제외 절). 점주의 것인지 플랫폼 운영자의 것인지는 그 PR이 정한다.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 앞 PR은 [terminal-authentication](2026-10-05-terminal-authentication.md), [payment-status-query](2026-10-06-payment-status-query.md)다.

## 변경 경로
- build.gradle.kts
- docker/**
- src/main/java/com/resilientpos/owner/**
- src/main/kotlin/com/resilientpos/owner/**
- src/main/java/com/resilientpos/store/**
- src/main/kotlin/com/resilientpos/store/**
- src/main/kotlin/com/resilientpos/terminal/**
- src/main/kotlin/com/resilientpos/platform/**
- src/main/resources/application.yaml
- src/main/resources/db/migration/V014__terminal_code.sql
- src/main/resources/db/migration/V015__owner_store.sql
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/wiki/domain-model.md
- docs/wiki/observability.md
- docs/wiki/entity-map.md
- docs/wiki/README.md
- docs/spec/2026-10-06-operator-authentication.md

## 인수 조건
1. 가입은 `201 {ownerId, email}`이고 이메일은 소문자로 정규화된다. 같은 이메일(대소문자만 다른 것 포함)은 `409 OWNER_EMAIL_DUPLICATED`, 형식이 틀린 이메일은 `400 INVALID_EMAIL`, 15자 미만이나 72바이트 초과 비밀번호는 `400 INVALID_PASSWORD`다. 저장된 비밀번호는 `{bcrypt}` 해시다.
2. 로그인은 `200`과 세션 쿠키이고, 로그인 전 세션 ID와 다른 ID다. 그 쿠키로 인증 경로를 부를 수 있다. 없는 계정·틀린 비밀번호는 같은 `401 UNAUTHENTICATED`다. 로그아웃(`204`) 뒤 같은 쿠키는 401이다.
3. 같은 이메일의 로그인 시도가 첫 시도부터 15분 창 안에서 5번을 넘으면, 그 창이 끝날 때까지 맞는 비밀번호로도 401이고 비밀번호를 비교하지 않는다. 그 전에 성공하면 카운터가 지워진다. 동시에 보낸 시도도 각자 세어진다. 카운터가 Redis에 닿지 못하면 비밀번호를 비교하지 않고 503 `SERVICE_UNAVAILABLE`이다.
4. `/owner/**`의 상태 변경 요청은 CSRF 토큰이 없거나 틀리면 `403 ACCESS_DENIED`이고 Facade를 부르지 않는다. `GET /owner/v1/csrf`가 준 토큰을 헤더에 실으면 통과한다. 세션 없이 인증 경로를 부르면 `401 UNAUTHENTICATED`다(상태 변경 요청은 CSRF 검사가 먼저라 토큰이 맞을 때만 401이다).
5. `/v1/**`의 단말 인증, `/actuator/health`, 시연 정적 자원의 동작은 바뀌지 않는다. 단말 토큰으로 `/owner/**`를 부르면 401이고, 점주 세션으로 `/v1/**`를 부르면 401이다.
6. 매장 생성은 `201 {storeId, name}`이고 목록은 내 매장만 생성 순으로 돌려준다. 빈 이름이나 100자 초과는 `400 INVALID_STORE_NAME`이다.
7. 남의 매장 또는 없는 매장으로 단말 endpoint를 부르면 `404 STORE_NOT_FOUND`이고 단말이 바뀌지 않는다. 내 매장 경로에 다른 매장의 단말 ID를 넣으면 `404 TERMINAL_NOT_FOUND`다.
8. 단말 등록은 `ACTIVE` 단말과 credential을 만들고 `201 {terminalId, terminalCode, token}`을 돌려준다. 그 토큰으로 `/v1/**`를 인증할 수 있다.
9. 같은 매장에 같은 코드로 등록하면 `409 TERMINAL_CODE_DUPLICATED`이고 단말이 늘지 않는다. 다른 매장은 같은 코드를 쓸 수 있다. 형식이 틀린 코드(소문자 포함)는 `400 INVALID_TERMINAL_CODE`다.
10. 회전은 `200 {token}`이고 새 토큰만 인증된다. 비활성 단말은 `409 TERMINAL_INACTIVE`, 모르는 단말은 `404 TERMINAL_NOT_FOUND`다.
11. 비활성화는 `200 {terminalId, status: INACTIVE}`이고 다시 불러도 같다. 그 뒤 그 단말의 토큰은 401이다.
12. 단말 목록은 그 매장의 단말만 코드 순으로 돌려주고 토큰은 싣지 않는다.
13. 매장 생성과 단말 등록·회전·비활성화는 커밋 뒤 감사 INFO 로그를 한 줄 남긴다. 로그와 DTO의 `toString` 어디에도 평문 토큰·비밀번호가 없다.
14. V014가 기존 행을 매장별 `LEGACY-1`, `LEGACY-2`…로 채운다. 그 뒤 `terminal_code`는 NOT NULL이고 `(store_id, terminal_code)`가 unique다. V015 뒤 `t_owner.email`이 unique다.
15. REST Docs 스니펫(`build/generated-snippets`)이 가입·로그인·로그아웃·CSRF·매장 2개·단말 4개 endpoint의 요청·응답 필드를 문서화한다. 401·403·404·409 예도 담는다.
16. `ModuleStructureTest`가 `owner`를 포함한 모듈 8개를 인식하고 통과한다.
17. 세션 저장소(Redis)에 닿지 못하면 다음 요청은 envelope의 `503 SERVICE_UNAVAILABLE`이고 HTML이 아니다: 세션 쿠키가 있는 인증 경로, 세션 쿠키가 있거나 없는 로그인(틀린 비밀번호도 비교 없이 503). 세션이 필요 없는 `GET /owner/v1/csrf`는 세션 쿠키가 있어도 204다. 같은 상태에서 단말 요청(`/v1/**`)은 세션 쿠키가 실려 와도 영향이 없다.
18. Redis에 저장된 세션 속성은 JSON이고 JDK 직렬화 바이트가 아니다. 로그인 → 인증 경로 → 로그아웃이 JSON 세션으로 동작한다. 허용 목록 밖의 타입이 든 세션은 역직렬화하지 않는다.

### Checkpoint와 검증 명령 (선택)
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| 단말 코드와 V014 (9·14) | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest'` | 없음 |
| 점주 계정·잠금 (1·3) | `./gradlew test --tests 'com.resilientpos.owner.*'` | 없음 |
| 점주 체인·세션·CSRF (2·4·5) | `./gradlew test --tests 'com.resilientpos.platform.*' --tests '*OwnerAuthentication*'` | 점주 계정 |
| 매장 (6) | `./gradlew test --tests 'com.resilientpos.store.*'` | 점주 체인 |
| 단말 관리 API·조회·감사 (7·8·10~13·15) | `./gradlew test --tests 'com.resilientpos.terminal.*'` | 매장 |

## 스키마 변경 검토
`V014__terminal_code.sql`이 `t_terminal`에 컬럼 하나와 unique 제약 하나를 더한다.

```sql
alter table t_terminal add column terminal_code varchar(32);

update t_terminal t
set terminal_code = 'LEGACY-' || s.rn
from (
    select id, row_number() over (partition by store_id order by created_at, id) as rn
    from t_terminal
) s
where t.id = s.id;

alter table t_terminal alter column terminal_code set not null;
alter table t_terminal add constraint ux_terminal_store_code unique (store_id, terminal_code);
```

`V015__owner_store.sql`이 표 둘을 만든다.

```sql
create table t_owner (
    id uuid primary key,
    email varchar(254) not null,
    password_hash varchar(255) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_owner_email unique (email)
);

create table t_store (
    id uuid primary key,
    owner_id uuid not null,
    name varchar(100) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create index ix_store_owner_id on t_store (owner_id);
```

- 질문 1(도출):
  - `terminal_code`는 도출할 수 없다. 점주가 현장에서 부르는 이름이라 다른 컬럼에 없다. 기존 행의 `LEGACY-순번`은 채우기 위한 값이지 도출한 사실이 아니다.
  - `t_owner`의 이메일과 비밀번호 해시는 계정 그 자체라 다른 곳에 없다.
  - `t_store.owner_id`는 소속의 정본이고 다른 곳에 없다(결정 1). `name`은 점주가 여러 매장을 고를 때 쓰는 이름이다.
  - 로그인 실패 횟수와 잠금 시각은 컬럼으로 두지 않는다. 업무 사실이 아니고, 잃어도 창 하나만큼 제한이 풀릴 뿐이라 Redis에 둔다(결정 6).
  - 세션 표도 두지 않는다. Spring Session Redis가 맡는다(결정 3).
- 질문 2(읽는 코드):
  - 단말 목록 조회가 `terminal_code`를 읽어 응답하고 정렬한다. 인증·회전·비활성화도 `TerminalEntity.toDomain()`에서 `TerminalCode`를 만들며 읽는다. 그래서 수동 수정으로 형식에 맞지 않는 코드가 들어가면, 그 단말의 `/v1` 인증이 401이 아니라 400 `INVALID_TERMINAL_CODE`가 된다. 코드를 쓰는 경로는 형식을 검사하는 등록 하나라 이를 받아들인다.
  - 로그인이 `email`로 행을 찾아 `password_hash`를 비교한다.
  - 소유 확인과 내 매장 목록이 `owner_id`를 읽고, 목록이 `name`을 응답하며 `created_at, id`로 정렬한다.
  - `OwnerEntity.toDomain()`은 저장된 이메일을 `OwnerEmail` 형식 검사로 다시 만든다. 쓰는 경로가 같은 검사를 거치므로 걸리지 않는다. 수동 수정으로 형식에 맞지 않는 값이 들어가면 그 계정의 로그인이 401이 아니라 400이 된다.
- 질문 3(나중에 채우기): 모두 이 PR에서 읽으므로 해당 없다. `terminal_code` 기존 행은 위 UPDATE로 채운다. `t_owner`·`t_store`는 새 표다.
- 질문 4(마지막 방어선):
  - `ux_terminal_store_code`가 "매장 안에서 코드 유일"의 유일한 방어선이다. 사전 조회를 하지 않으므로 순차 등록과 동시 등록 모두 여기서 걸린다.
  - `ux_owner_email`이 "이메일은 계정 하나"의 유일한 방어선이다. 정규화(소문자)는 애플리케이션이 저장 전에 하므로 대소문자만 다른 두 값이 들어오지 않는다. 수동 INSERT로 대문자가 들어가는 것은 막지 않는다.
  - 두 제약 모두 soft delete된 행에도 걸린다. 지우는 명령이 없다. 지우는 명령이 생기면 그 PR이 partial unique로 바꿀지 정한다.
  - `ux_terminal_store_code`의 인덱스는 `store_id`가 앞이라 매장별 단말 목록 조회도 탄다.
  - `t_store`에는 unique가 없다. 같은 이름의 매장을 허용한다.
- 질문 5(정본):
  - `t_terminal.terminal_code`, `t_owner`, `t_store.owner_id`가 각자 하나뿐인 정본이다. 캐시하지 않는다.
  - 세션은 점주 ID만 담고 소속은 담지 않아, 소속이 세션과 DB 두 곳에 있지 않다.
  - Redis의 실패 카운터는 정합성이 아니라 완화 수단이다. 카운터 값을 잃으면 창 하나만큼 제한이 풀린다. Redis에 닿지 못할 때는 로그인을 거절한다(결정 6).
- 질문 6(check-then-act):
  - 단말 등록과 가입은 읽고 판단하지 않고 INSERT만 한다. 경합은 unique가 막고 adapter가 409로 바꾼다.
  - 소유 확인은 읽은 뒤 단말을 바꾸지만 `owner_id`를 바꾸는 명령이 없어서 그 사이에 결과가 달라지지 않는다.
  - 실패 카운터는 판단 전에 먼저 센다. INCR과 TTL은 Lua 스크립트 하나라 원자적이고, 동시 시도도 각자 세어져 창 안의 비교는 5번을 넘지 않는다(결정 6).
- 질문 7(락):
  - V014: `ALTER TABLE … ADD COLUMN`(nullable, 기본값 없음)은 메타데이터 변경이지만 `ACCESS EXCLUSIVE`를 잡는다. V014가 한 트랜잭션이라 그 락이 커밋까지 남는다. `SET NOT NULL`의 전체 검사와 `ADD CONSTRAINT UNIQUE`(역시 `ACCESS EXCLUSIVE`)는 행 수에 비례한다. 그동안 `t_terminal`을 읽는 단말 인증이 기다린다.
  - 배포 환경에는 등록 입구가 없어 행이 없거나 몇 개뿐이다. 즉시 끝나므로 `CONCURRENTLY`로 나누지 않는다.
  - 긴 트랜잭션 뒤에서 락을 기다리며 인증을 세우지 않도록 첫 문장 앞에 `set local lock_timeout = '5s'`를 둔다(V005·V008·V011과 같다).
  - 제약을 걸기 전 위반 행은 0건이다. `row_number()`가 매장별로 서로 다른 순번을 주고 UPDATE가 모든 행(삭제 행 포함)을 채우기 때문이다.
  - V015는 새 표라 기존 표에 락을 잡지 않고, 행이 없어 인덱스 빌드가 즉시 끝난다.
- 질문 8(이전 버전):
  - 이전 버전은 새 컬럼과 새 표를 매핑하지 않아 `validate`가 통과한다. 인증·회전·비활성화는 그대로 동작한다.
  - 이전 버전의 `register`는 `terminal_code` 없이 INSERT해서 NOT NULL에 걸린다. 이전 버전에는 HTTP 등록 입구가 없고 main 코드에 Facade 호출자도 없어 받아들인다.
- 질문 9(payload): 이벤트 payload와 캐시되는 DTO에 실리지 않는다. 비밀번호 해시는 어떤 응답·로그에도 나가지 않는다. unique 위반 때 PostgreSQL이 보내는 상세(`Key (email)=(…)`)가 Hibernate WARN 로그에 이메일을 남겨서(실측), pgjdbc `logServerErrorDetail=false`로 예외 메시지와 로그에서 상세를 뺀다. 제약 이름은 상세가 아니라 메시지 본문에 있어 409 판정은 그대로다. 세션에는 점주 ID 문자열을 principal로 하는 인증 객체만 JSON으로 들어간다(결정 13).
- 대조 기록: code-reviewer(읽기 전용 에이전트, 구현자 아님), 2026-10-06. V014__terminal_code.sql, TerminalEntity, TerminalPersistenceAdapter, TerminalQueries, TerminalManagement, Terminal, SchemaMigrationTest를 질문 1~9의 V014 답과 대조했다. 불일치 2건을 받아 V014와 위 답에 반영했다. 하나는 질문 7이다. V014에 `lock_timeout`이 없었고, unique 추가의 락을 `SHARE`로 적었으며, 위반 행 확인이 없었다. 다른 하나는 질문 2로, 인증·회전·비활성화의 `toDomain` 읽기가 빠져 있었다. V015: reviewer-claude(읽기 전용 에이전트, 구현자 아님), 2026-10-07. V015__owner_store.sql, OwnerEntity, OwnerPersistenceAdapter, StoreEntity, StorePersistenceAdapter, StoreQueries, OwnerManagement, StoreManagement를 질문 1~9의 답과 대조했다. 불일치 4건을 받아 코드와 위 답에 반영했다. 질문 2는 `OwnerEntity.toDomain`의 이메일 재검사와 매장 목록의 `created_at, id` 정렬 읽기가 빠져 있었다. 질문 6은 카운터가 비교 뒤에 세고 INCR·EXPIRE가 따로라 동시 시도와 영구 잠금이 열려 있었다(증가 먼저와 Lua로 고쳤다). 질문 9는 unique 위반 상세의 이메일이 로그에 남았다(`logServerErrorDetail=false`로 고쳤다).

## 검증
- `./gradlew test`. 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓰고, 세션은 그 Redis에 저장된다.
- 정상: 가입 → CSRF 토큰 → 로그인 → 매장 생성 → 단말 등록 → 그 토큰으로 주문 생성. 회전 뒤 새 토큰만 통과한다. 비활성화 뒤 401이다. 목록이 코드 순이다. 로그아웃 뒤 401이다.
- 실패:
  - 이메일 중복 409, 이메일·비밀번호·매장 이름·단말 코드의 형식 오류 400.
  - 로그인 실패 401, 5회 실패 뒤 잠금 401, 세션 없음 401, CSRF 없음 403.
  - 남의 매장 404, 다른 매장 단말 404, 코드 중복 409, 비활성 단말 회전 409.
- 수동: `docker compose -f docker/docker-compose.yaml up -d`와 `bootRun`으로 띄워 curl(쿠키 jar)로 가입 → 로그인 → 매장 생성 → 단말 등록 → sync를 한 번 부른다. 결과는 결과 절에 남긴다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md):
  - bounded context 목록에 `owner`를 더해 8개로 고친다. `store` 행에 매장 이름과 소유 점주를 더한다.
  - `terminal` 행의 "매장 안에서 코드 유일"이 구현됐음을 적는다. §4.7 사례에 `ux_terminal_store_code`·`ux_owner_email`을 적는다.
  - 용어집의 `operator`를 플랫폼 운영자로 고치고 `owner`(점주)를 더한다.
- [관측성](../wiki/observability.md): 감사 로그 항목과, 토큰·비밀번호·이메일 미기록을 적는다.
- [엔티티 지도](../wiki/entity-map.md): `TerminalEntity`의 `terminalCode`, `OwnerEntity`, `StoreEntity`와 점주 흐름을 더한다.
- [wiki 인덱스](../wiki/README.md): 엔티티 수와 "운영자 흐름" 문구를 고친다.
- 현재 상태(볼트 링크라 이 PR의 변경 경로에 없다)에는 다음을 적는다.
  - 점주 계정·세션·매장·단말 관리 API와 `ACCESS_DENIED`, `ErrorType` 항목 수.
  - "HTTP 등록 API가 없다 … 외부 노출은 여전히 하지 않는다"를 고친다.
  - 외부 노출의 남은 조건(이메일 인증, ingress에서 Actuator 차단, rate limit)을 적는다.
- 요구사항 변화: 참고 문서의 요구(운영 API의 별도 권한, 행위자 기록, 매장 안 단말 코드 유일)를 처음 구현한다. 요구 자체는 바뀌지 않는다. 다만 "운영자"를 점주와 플랫폼 운영자의 두 주체로 나눴다(결정 1). [terminal-authentication](2026-10-05-terminal-authentication.md) 결정 1·2·4는 이 PR이 대체한다.

## 분할 계획
개발 컨텍스트는 하나다. 점주가 누구인지 확인하고 그 점주가 자기 매장의 단말을 관리하게 하는 일이다. 여기에 그것이 요구하는 점주 계정·세션, 소속을 담는 매장, 점주가 단말을 고를 단말 코드가 딸려 온다. 계정 작업을 별도 PR로 나누지 않은 것은 사용자 결정이다(2026-10-07). 단말 관리 API가 매장 단위 인가 없이는 열 수 없기 때문이다. 플랫폼 운영자·DLT 재투입·메일 인프라는 다른 컨텍스트라 각자의 PR로 뺐다. 범위 조정 시점(origin/main 2f79976)에 열린 PR은 이 PR(#90) 하나뿐이라 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 9·14, `TerminalCode` 형식과 V014 채우기, 코드 중복 409 (`TerminalCodeTest`, `TerminalManagementIntegrationTest`의 같은 매장 중복·다른 매장 허용, `SchemaMigrationTest`의 V014 채우기·baseline) | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest'`. 형식 검사 없는 `TerminalCode`와 미매핑 상태에서 5건 실패: `TerminalCodeTest` 거절 사례 assertion 실패, 중복 등록이 예외 없이 성공해 `assertFailsWith` 실패, baseline 버전 목록에 014 없음, V014 채우기는 `No migration with a target version 014`(V014 파일 없음), 다른 매장 허용은 `terminal_code` 컬럼 없음 SQL 오류 | 같은 명령 통과(BUILD SUCCESSFUL). V014는 spec SQL 그대로, `TerminalCode` 정규식 검사, adapter가 persist 직후 flush해 제약명 `ux_terminal_store_code`만 `TERMINAL_CODE_DUPLICATED`(CONFLICT)로 변환 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest'` 통과, `./gradlew test` 전체 통과 |
| 리뷰 반영: V014 `lock_timeout`, 코드 중복 판정을 SQLState `23505`와 제약 이름으로 | RED 미관측(characterization). Hibernate 7.4.5 `PostgreSQLDialect`의 제약 이름 추출기가 23505 메시지의 영어 템플릿을 파싱한다는 것을 sources jar로 확인했다. Testcontainers PostgreSQL은 영어 메시지라 기존 판정도 통과해서 실패를 재현하지 못했다. 기존 중복 409 통합 테스트가 회귀를 덮는다 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest'` 통과 | `./gradlew test` 전체 통과 |
| 인수 1·3, 가입과 로그인 실패 제한(비즈니스 계층, HTTP 제외). `OwnerEmailTest`, `OwnerPasswordTest`, `OwnerCommandTest`, `OwnerManagementTest`(4회 실패 뒤 성공·5회 뒤 맞는 비밀번호도 거절·성공 시 초기화·Redis 오류 fail-open), `OwnerManagementIntegrationTest`(가입·대소문자 중복 409·`{bcrypt}` 저장·로그인·Redis 키와 TTL) | `./gradlew test --tests 'com.resilientpos.owner.*'`. 본문이 `TODO()`인 선언만 둔 상태에서 23건 중 21건 실패: 값 객체·Facade 사례가 `NotImplementedError`, `INVALID_EMAIL`·`INVALID_PASSWORD` 기대는 `assertFailsWith`가 `NotImplementedError`를 받아 실패(`OwnerCommandTest` 2건은 선언에 `toString`을 함께 써서 characterization). 통합 테스트는 V015 `t_owner`를 먼저 둔 상태 | 같은 명령 통과(23건). V015는 `t_owner`만 spec SQL 그대로, 이메일 정규화·비밀번호 정책은 값 객체, 제한은 `LoginFailureCounter` port(Redis 구현이 INCR·첫 실패 15분 TTL·fail-open)와 Facade의 5회 경계 | `./gradlew test --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.SchemaMigrationTest'` 통과(baseline에 015, 표 목록에 `t_owner`, 모듈 목록에 `owner`), `./gradlew test` 전체 530건 통과 |
| 인수 2·4·5, 점주 체인·세션·CSRF와 다른 경로 무영향. `OwnerAuthenticationIntegrationTest`(가입→CSRF→로그인→세션 쿠키로 200, 로그인 전 세션 ID 폐기와 새 ID, 로그아웃 204 뒤 401, 세션 없음·모르는 쿠키 401에 WWW-Authenticate 없음, CSRF 없음·불일치 403 ACCESS_DENIED와 계정 미생성, 틀린 비밀번호·없는 계정 같은 401, 단말 토큰으로 owner 401, 점주 세션으로 v1 401 Bearer, actuator health 200, 쿠키 설정 바인딩), `OwnerControllerTest`(REST Docs: 가입 201·400 둘·409, 로그인 200·401, 로그아웃 204, csrf 204, CSRF 누락 403), `OwnerRequestsTest`, `UnauthenticatedExceptionTest`의 challenge, `OwnerManagementTest`의 정규화 이메일, `TerminalAdminControllerTest`·`TerminalAdminApiIntegrationTest`를 점주 세션 기준으로 맞춤 | `./gradlew test --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.platform.*Owner*' --tests 'com.resilientpos.platform.UnauthenticatedExceptionTest' --tests 'com.resilientpos.terminal.internal.adapter.web.TerminalAdmin*'`. 체인이 default CSRF만 켠 최소 스텁이고 Controller 본문이 `TODO()`인 상태에서 61건 중 24건 실패: 가입·로그인·CSRF 토큰 요청이 permitAll이 아니라 403, 로그아웃 경로 404, CSRF 거절 응답이 envelope 없는 빈 본문(`$.result` 없음), 로그인 뒤 XSRF-TOKEN 쿠키 없음, 통합 테스트는 `GET /owner/v1/csrf`에 쿠키가 없어 `XSRF-TOKEN 쿠키가 없다`, 세션 없음 요청이 401이 아니라 403, `SessionRepository` 제네릭 주입 실패(테스트 오류라 고침) | 같은 명령에서 체인(`csrf.spa()`, permitAll 셋, logout 204, 요청 캐시 끔, entry point·access denied는 `HandlerExceptionResolver`), `OwnerController`, `UnauthenticatedException.challenge`, advice의 조건부 `WWW-Authenticate`를 넣고 `application.yaml`에 쿠키 설정을 둔 뒤 통과. 쿠키 속성은 MOCK 웹 환경이 서블릿 컨텍스트 경로(`WarDeploymentConfiguration`)로 쿠키를 만들어 MockMvc에서 볼 수 없어, 일회용 RANDOM_PORT 프로브(삭제함)로 가입→로그인을 실제 서버에 보내 `SESSION=…; Path=/; Secure; HttpOnly; SameSite=Lax`를 관측했고 테스트는 `ServerProperties` 바인딩만 검사한다. 슬라이스 CSRF는 `csrf()` post-processor가 `csrf.spa()`와 맞지 않아(헤더에 인코딩된 값이 실려 403) 운영과 같은 쿠키·헤더 고정 토큰으로 재현했다 | `./gradlew test` 전체 542건 통과, 실패 0, 건너뜀 0. 이번 동작에서 로그인 응답 이메일 때문에 `OwnerManagement.logIn`의 반환을 `OwnerCommand.LogIn.Result`로 바꿨고 그 단위·통합 테스트를 같이 고쳤다 |
| 인수 6, 매장 생성과 내 매장 목록. `StoreTest`(이름 trim·0자·공백만·1자·100자·101자·code point), `StoreManagementIntegrationTest`(저장·내 매장만 생성 순과 이름 중복·requireOwned가 남의 매장과 없는 매장에 같은 STORE_NOT_FOUND), `StoreControllerTest`(REST Docs: 201, 400 INVALID_STORE_NAME, 목록 200, 401, CSRF 없음 403, 감사 로그 로그 캡처), `SchemaMigrationTest`의 표 목록에 `t_store` | `./gradlew test --tests 'com.resilientpos.store.*' --tests 'com.resilientpos.SchemaMigrationTest'`. V015에 `t_store`·`ix_store_owner_id`와 엔티티·adapter·queries를 두고 `Store.create`·Facade·Controller 본문을 `TODO()`로 둔 상태에서 20건 중 11건 실패: 도메인·통합 사례는 `NotImplementedError`, 이름 거절 기대는 `assertFailsWith`가 `NotImplementedError`를 받아 실패, 슬라이스 3건은 상태 500(201·200·400 기대). 401·403 슬라이스와 표 목록 사례는 기존 체인·V015로 통과(characterization). 슬라이스 첫 실행의 `ParameterResolutionException`은 `TerminalManagement` mock 누락이라는 테스트 오류여서 `OwnerControllerTest`처럼 mock을 더해 고쳤다 | 같은 명령에 `ModuleStructureTest`·`DependencyDirectionTest`를 더해 통과(27건). `Store.create`가 trim 뒤 code point 1~100을 검사하고 `StoreManagement`가 `Tx.masterTx`로 저장하며, 읽기(`findByOwner`·소유 확인)는 `StoreQueries`(Kotlin JDSL, `@ReadOnlyTransactional`)가 맡는다. 읽기용 Facade는 따로 두지 않고 `StoreManagement` 하나로 했다 | `./gradlew test` 전체 555건 통과, 실패 0, 건너뜀 0 |
| 인수 7·8·10~13·15, 매장 아래 단말 관리 API·소유 확인·감사 로그·REST Docs. `TerminalManagementIntegrationTest`(다른 매장 ID로 회전·비활성화 시 TERMINAL_NOT_FOUND, credential·상태·인증 불변), `TerminalTest`(`requireStore`), `TerminalOwnerControllerTest`(REST Docs: 등록 201·400·409, 회전 200·404·409, 비활성화 200 멱등·404, 목록 200 코드 순, 남의 매장 404 STORE_NOT_FOUND와 단말 Facade 미호출, 401, CSRF 없음 403, 경로 UUID 오류 400), `TerminalOwnerApiIntegrationTest`(가입→로그인→매장 생성→등록→토큰으로 주문→회전→비활성화 멱등→목록, 다른 점주의 등록·목록·회전·비활성화 404와 단말 불변, 내 매장 경로에 다른 매장 단말 ID면 404, 감사 로그와 평문 토큰 부재) | `./gradlew test --tests 'com.resilientpos.terminal.internal.application.TerminalManagementIntegrationTest' --tests 'com.resilientpos.terminal.internal.adapter.web.TerminalOwnerControllerTest'`. 커맨드에 `storeId`를 더하고 컨트롤러를 새 경로로 옮기되 소유 확인과 매장 대조가 없는 상태에서 3건 실패: Facade 사례는 다른 매장 단말이 회전·비활성화되어 TERMINAL_NOT_FOUND 기대가 `assertFailsWith`에서 실패, 슬라이스는 `requireOwned` 호출이 없어 `WantedButNotInvoked`와 404 기대 `AssertionError` | 같은 명령에 `TerminalTest`를 더해 통과. `Terminal.requireStore`(다른 매장이면 `Terminal.notFound()`, 잠근 뒤 `requireActive` 앞에서 호출)와 컨트롤러가 모든 endpoint에서 `StoreManagement.requireOwned`를 먼저 호출한다. 소유 확인 뒤 단말 Facade를 부르므로 다른 매장 ID는 단말 Facade에 닿지 않는다. 컨트롤러·DTO·테스트 이름의 `Admin`을 `Owner`로 바꿨다. HTTP 통합 테스트는 구현 뒤에 작성해 바로 통과(characterization) | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.platform.*Owner*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.DependencyDirectionTest'` 통과, `./gradlew test` 전체 560건 통과, 실패 0, 건너뜀 0. `OwnerAuthenticationIntegrationTest`는 유효 세션 200 확인이 소유 매장을 요구하게 되어 `GET /owner/v1/stores`로 바꿨다 |
| 인수 14·16, V015와 모듈 8개 (`SchemaMigrationTest`의 baseline 015·표 목록 `t_owner`·`t_store`, `OwnerManagementIntegrationTest`의 대소문자 중복 409, `ModuleStructureTest`의 `owner` 인식) | 별도 RED 없음. V015는 인수 1·6 동작의 GREEN에서 들어왔다. `t_owner`를 둔 뒤 `./gradlew test` 전체 회귀에서 `SchemaMigrationTest`의 "테이블은 마이그레이션이 만든 것뿐이다"가 기대 목록에 없는 `t_owner`로 실패해 목록을 고쳤다(인수 1 행). `ModuleStructureTest`는 `owner` 패키지와 함께 목록을 고쳐 바로 통과했다(characterization) | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.DependencyDirectionTest'` 통과 | `./gradlew test` 전체 통과(결과 절) |
| 리뷰 반영(2차): 항목 1 로그인 시도 증가 먼저·Lua 원자화, 2 unique 상세 로그 차단, 3 `__Host-` 쿠키와 실제 응답 속성, 4 제어·서식 문자 거절, 5 CSRF 거절 로그, 6 Boot 기본 사용자 제거, 7 정리. `OwnerManagementTest`(6번째 시도 거절·시도를 비교 전에 셈·fail-open을 `IllegalStateException`과 `RedisConnectionFailureException`으로), `OwnerManagementIntegrationTest`(시도마다 TTL·카운터 4에서 병렬 8개 중 성공 1개·중복 가입 출력에 이메일·비밀번호 없음), `OwnerEmailTest`·`StoreTest`(제어·서식 문자), `ApiAdviceTest`(CSRF 거절 로그), `OwnerAuthenticationIntegrationTest`(`UserDetailsService` 빈 없음), `OwnerCookieIntegrationTest`(RANDOM_PORT 실제 `Set-Cookie`와 출력에 평문 비밀번호 없음) | 항목별로 클래스를 좁혀 `./gradlew test --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.store.*' --tests 'com.resilientpos.platform.*'` 형태로 관측했다. 항목 1: 포트에서 `count`를 둔 채 `increment`가 값을 돌려주게만 바꾼 상태에서 4건 실패(6번째 시도 뒤 카운터가 6이 아니라 5, 비교 전 증가 없음, 통합의 카운터 기대 `6`이 `5`, 병렬 8개가 모두 성공해 성공 `1` 기대 실패). fail-open 사례의 실패는 테스트가 저장소를 재사용한 오류라 고쳤다. 항목 2: 중복 가입 출력에 이메일이 남아 `assertFalse` 실패. 항목 3: `__Host-XSRF-TOKEN` 쿠키가 없어 실제 응답 테스트 실패. 항목 4: 제어·서식 문자 사례가 예외 없이 통과해 실패. 항목 5: 로그에 제출 토큰 문자열이 남아 실패. 항목 6: `UserDetailsService` 빈이 있어 실패. 항목 7은 삭제·주석이라 RED 미실행. `증가 뒤 키에는 시도마다 TTL이 있다`는 EXPIRE가 빠지는 장애를 주입하지 못해 RED를 만들지 못한 characterization이다 | 같은 명령 통과. 항목 1: `LoginFailureCounter.increment`가 증가 뒤 값을 돌려주고(`count` 삭제) Redis 구현이 Lua 스크립트 한 번으로 INCR·첫 증가 PEXPIRE, `OwnerManagement.logIn`이 이메일 형식 확인 직후 증가해 5 초과면 비교 없이 거절. 항목 2: `application.yaml`의 pgjdbc `logServerErrorDetail: false`(제약 이름은 메시지 본문에 남아 중복 409 판정이 그대로 통과). 항목 3: `server.servlet.session.cookie.name`과 `OwnerCsrfTokens`(체인과 `OwnerController`가 공유하는 저장소, `__Host-XSRF-TOKEN`·`Path=/`·Secure). MockMvc는 세션 쿠키 설정을 적용하지 않아 이름 `SESSION`을 헬퍼 상수로 두고 실제 이름·속성은 RANDOM_PORT 테스트가 고정. 항목 4: `\p{Cc}`·`\p{Cf}` 정규식. 항목 5: `handleAccessDenied`가 예외를 로그에 싣지 않음. 항목 6: `spring.autoconfigure.exclude`에 `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`. 항목 7: `Store.restore`·`OwnerEmail.normalize` 삭제와 Redis 주석 | `./gradlew test` 전체 567건 통과, 실패 0, 건너뜀 0 |
| 리뷰 반영(3차): Lua 스크립트가 TTL 없는 키에도 TTL을 건다(`n == 1`에서 `PTTL < 0`으로). `OwnerManagementIntegrationTest`의 `TTL 없이 남은 키는 다음 시도에서 TTL을 얻어 영구 잠금이 되지 않는다` | `./gradlew test --tests '*OwnerManagementIntegrationTest'`. 옛 조건(`n == 1`)에서 TTL 없이 값 9를 둔 키로 로그인하자 `ttl=-1`로 assertion 실패 | 같은 명령과 `*OwnerManagementTest` 통과 | `./gradlew test` 전체(결과 절) |
| 결정 12·13, 인수 17·18: 세션 저장소 장애 503과 세션 JSON 직렬화 (`OwnerSessionStoreFilterTest`, `ApiAdviceTest`의 503·일반 DataAccessException 500, `OwnerSessionStoreOutageIntegrationTest`, `OwnerSessionSerializationIntegrationTest`) | 장애(12): `./gradlew test --tests 'com.resilientpos.platform.OwnerSessionStoreFilterTest' --tests 'com.resilientpos.platform.ApiAdviceTest'`. 필터·예외 선언만 두고 체인을 그대로 통과시킨 상태에서 3건 실패: 503 기대가 500, DataAccessException이 resolver에 가지 않음, 등록 urlPatterns가 비어 있음(커밋된 응답 재던짐과 SerializationException 비변환은 이미 통과하는 characterization). 통합은 필터를 구현한 뒤 `OwnerSessionStoreOutageIntegrationTest`를 쓰고, 필터가 항상 다시 던지게 바꾼 임시 변형에서 실행해 2건 실패(읽기 503 기대가 500, 로그인 503 기대가 INTERNAL_ERROR envelope 500)를 관측한 뒤 되돌렸다. 단말 sync 200은 처음부터 통과(characterization). 직렬화(13): `./gradlew test --tests 'com.resilientpos.platform.OwnerSessionSerializationIntegrationTest'`. 기본 JDK serializer에서 2건 실패: 저장된 SecurityContext가 JDK 바이트(`ACED`), JSON 값을 읽다 `SerializationException`이 요청 밖으로 올라옴(옛 JDK 세션 사례는 JDK 형식 그대로라 통과) | 같은 명령 통과. 12: `ErrorType.SERVICE_UNAVAILABLE`(503, ERROR), 전용 예외 `SessionStoreUnavailableException`과 advice 매핑(다른 `DataAccessException`은 그대로 500), `OwnerSessionStoreFilter`를 `FilterRegistrationBean`으로 `/owner/*`에 `SessionRepositoryFilter.DEFAULT_ORDER - 1`로 등록. 필터가 resolver를 직접 받으면 웹 서버 생성 때 MVC·JPA 빈이 먼저 만들어져 기동이 실패해 `ObjectProvider`로 오류 때 꺼낸다. 13: `springSessionDefaultRedisSerializer` 빈을 `SecurityJacksonModules`를 단 `JsonMapper`의 serializer로 바꾸고, `GenericJacksonJsonRedisSerializer`가 최상위 `@class`를 허용 목록 없이 읽을 타입으로 삼는 것(허용 목록 밖 `GadgetProbe`가 만들어짐을 관측)을 막으려 읽을 타입을 `Object`로 고정하는 하위 클래스를 두었다. 읽지 못하는 값은 null로 다루고 세션 메타데이터가 없으면 이미 만료된 세션으로 돌려줘 저장소가 지운다. principal은 `UUID`에서 UUID 문자열로 바꿨다(Security 역직렬화기가 객체가 아닌 principal은 `asString()`으로만 읽어 `["java.util.UUID",…]` 배열 형태에서 실패). `@AuthenticationPrincipal` 파라미터 타입을 `String`으로 맞췄다 | `./gradlew test` 전체 580건 통과, 실패 0, 건너뜀 0. `check_pr.py --base origin/main --worktree` PASS |
| 🔴 리뷰 포인트 행별 점검 반영: 결정 6 fail-closed, 결정 12의 `FlashMapManager`·로그인 세션 조작 503 (`OwnerSessionStoreOutageIntegrationTest`의 세션 쿠키가 있는 로그인·csrf·단말 요청과 카운터 장애 로그인, `OwnerManagementTest`의 Redis 오류 503·clear 실패 로그인 성공, 로그아웃 통합 테스트의 CSRF 쿠키 만료) | `./gradlew test --tests 'com.resilientpos.platform.OwnerSessionStoreOutageIntegrationTest'` 기존 구현에서 7건 중 3건 실패: 세션 쿠키가 있는 단말 sync가 200이 아니라 Tomcat HTML 500, 카운터 장애에서 틀린 비밀번호가 503이 아니라 401 UNAUTHENTICATED, 쿠키 단 `GET /owner/v1/csrf`는 204가 아니라 503. 쿠키 단 로그인(503)과 쿠키 없는 맞는 비밀번호 로그인(503)은 이미 통과(characterization). `OwnerManagementTest`는 옛 `RedisLoginFailureCounter`에서 Redis 오류 사례 1건 실패(예외 없이 로그인 성공), clear 실패 사례는 통과. 로그아웃 CSRF 쿠키 만료는 이미 `Max-Age=0`이라 통과 | 같은 명령 통과. `flashMapManager` 빈(아무것도 하지 않음)과 `spring.mvc.publish-request-handled-events: false` 둘 다 필요하다: 빈만 두면 `FrameworkServlet.publishRequestHandledEvent`가 `WebUtils.getSessionId`로 세션을 읽어 단말 sync·로그인이 chunked 응답 오류로 깨지고, 빈을 빼면 3건이 HTML 500이다. `increment`는 Redis 오류(`DataAccessException`·`IllegalStateException`)를 `SessionStoreUnavailableException`(생성자 인자를 `RuntimeException`으로 넓힘)으로 던지고 `clear`는 WARN만 남긴다. `OwnerController.logIn`은 세션 ID 교체·컨텍스트 저장의 `DataAccessException`만 감싼다. 세션 쿠키가 있는 `GET /owner/v1/csrf`는 세션 저장소가 필요 없어 204다 | `./gradlew test` 전체 586건 통과, 실패 0, 건너뜀 0 |
| 결정 8: 도메인 404 로그 수준을 INFO로 (`ApiAdviceTest`) | 미실행 | 미실행 | 미실행 |

## 결과
- `./gradlew test` 전체 586건 통과(실패 0, 건너뜀 0). 인수 조건 1~18을 구현했다. 각 동작의 RED·GREEN 관측은 TDD 검증 기록에 있다.
- 수동 확인(2026-10-07, compose의 PostgreSQL·Redis·Kafka와 `bootRun`, curl 쿠키 jar). 첫 실행은 리뷰 반영 전(05a9b43), 둘째 실행은 반영 뒤(4966d92)다.
  - 첫 실행: CSRF 토큰 없는 가입은 403 `ACCESS_DENIED`다. 대문자 이메일로 가입하면 소문자로 저장되고, 같은 이메일은 409 `OWNER_EMAIL_DUPLICATED`다. 로그인 전 매장 목록은 401이다.
  - 둘째 실행: 기동 로그에 Boot 기본 사용자 비밀번호가 없다. 로그인 응답의 쿠키는 `__Host-SESSION=…; Path=/; Secure; HttpOnly; SameSite=Lax`와 `__Host-XSRF-TOKEN=…; Path=/; Secure`였다.
  - 첫 실행: 매장 생성(이름 앞뒤 공백 제거) → 단말 등록 201 → 그 토큰으로 빈 배치 sync 200 → 목록 → 비활성화 200 `INACTIVE` → 같은 토큰의 sync 401 순서로 동작했다. 모르는 매장은 404 `STORE_NOT_FOUND`다. 로그아웃 204 뒤에는 401이고 `WWW-Authenticate`가 없다.
  - 첫 실행: 틀린 비밀번호 5번 뒤 맞는 비밀번호도 401이었다. 감사 로그는 가입·로그인·매장 생성·등록·비활성화 다섯 줄이었다. 로그 파일에서 평문 토큰·비밀번호는 0건이었다. 이메일은 중복 가입의 Hibernate WARN(PostgreSQL 상세)에서 1건 나왔고, 그것이 리뷰 반영 2차의 근거다. 둘째 실행에서는 가입·중복 가입 409·로그인·매장·등록·sync 200 뒤 로그 파일의 이메일이 0건이었다.
  - 둘째 실행: Redis 컨테이너를 멈추자 점주 요청은 Tomcat 기본 HTML 500이었고, 같은 시각 단말 sync는 200이었다. Redis를 다시 띄우자 점주 요청이 200으로 돌아왔다.
- 구현 중 판단:
  - 로그인은 form login이 아니라 Controller가 자격을 확인한 뒤 세션 ID를 바꾸고, CSRF 토큰을 다시 만들고, `SecurityContext`를 세션에 저장한다. CSRF 저장소는 체인과 같은 인스턴스(`OwnerCsrfTokens`)다.
  - `UnauthenticatedException`의 `WWW-Authenticate` 기본값은 없음이고 단말 인터셉터만 `Bearer`를 넘긴다. 비즈니스 계층(`OwnerManagement`)이 HTTP 헤더를 알지 않게 하려는 것이다.
  - 소유 확인은 Controller가 먼저 부르고(`StoreManagement.requireOwned`), 회전·비활성화는 Facade가 행을 잠근 뒤 `Terminal.requireStore`로 다시 확인한다. 그래서 남의 매장에 형식이 틀린 코드를 보내도 400이 아니라 404다. `TerminalManagement.register`는 매장 존재·소유를 확인하지 않으므로 다른 호출자가 생기면 같은 확인을 먼저 부른다.
  - MockMvc(MOCK 환경)는 `server.servlet.session.cookie.*`를 적용하지 않아 테스트 헬퍼의 세션 쿠키 이름은 `SESSION`이다. 운영 이름과 속성은 RANDOM_PORT 테스트(`OwnerCookieIntegrationTest`)가 고정한다.
  - Boot 4.1.1의 `UserDetailsServiceAutoConfiguration`은 어떤 체인도 쓰지 않는 기본 사용자를 만들고 비밀번호를 WARN으로 남겨서 `spring.autoconfigure.exclude`로 껐다.
- 한계:
  - 세션 크기(추정 1~2KB)와 요청당 Redis 왕복은 실측하지 않았다. 부하테스트(#77)에서 본다.
  - 운영 Redis의 인증·TLS가 외부 노출의 전제다. Redis에 쓸 수 있는 주체는 세션을 위조할 수 있다.
  - 이메일 SHA-256 키는 알려진 이메일 목록으로 되돌릴 수 있는 가명화 수준이다.
  - 단말·매장 목록 정렬은 DB collation의 문자열 순이라 `LEGACY-10`이 `LEGACY-2`보다 앞에 온다.
  - 가입 409가 계정 존재를 알리므로 로그인의 더미 해시 비교는 보조 수단이다.
- 🔴 리뷰 포인트 행별 점검(spring-backend-specialist, 2026-10-08). PR 본문 리뷰 포인트 표의 행마다 '봐야 할 것'을 코드와 대조했고 P0은 없었다.
  - 결함 A(P1): 세션 쿠키가 실린 요청에서 MVC가 세션을 읽었다. 경로는 `FlashMapManager`와 요청 처리 이벤트 둘이다. 그래서 Redis 장애 때 쿠키가 실린 단말 sync가 Tomcat HTML 500이었다(RED 관측). 결정 12대로 고쳤다.
  - 결함 B(P1): 카운터가 fail-open이라 Redis 장애 중 틀린 비밀번호는 401, 맞는 비밀번호는 503이 되어 판별 신호가 생겼다(RED 관측). 결정 6을 fail-closed로 바꿨다.
  - 결함 C(P3): 503이어도 등록·회전이 커밋됐을 수 있다. 결정 12, REST Docs, 관측성 wiki에 확인 방법을 적었다.
  - P4: 로그아웃의 CSRF 쿠키 만료 단언과 context-path 주석을 넣었다. 도메인 404의 로그 수준(WARN)과 `ErrorType.NOT_FOUND`(INFO)의 차이는 사람이 정할 일로 남겼다.
  - 그 밖의 행(체인 범위, 세션 ID 교체, CSRF 저장소 공유, 증가 먼저와 Lua, 마이그레이션과 adapter 판정, 소유 확인 순서, 엔티티 규칙, 직렬화기 분리)은 근거와 함께 확인됐다.
  - 결정 12·13을 구현하면서 찾은 것: Spring Data Redis 4.1.1 `GenericJacksonJsonRedisSerializer`가 최상위 `@class`를 허용 목록 검사 없이 읽을 타입으로 삼는다. 하위 클래스에서 읽을 타입을 `Object`로 고정해 막았다. 업스트림 보고는 하지 않았다.
  - 간헐 실패 원인: 본문 변환 실패 로그에 입력 조각이 남았다. 여기에 `CapturedOutput.all`이 클래스 단위 캡처(앞 테스트 출력)까지 이어 붙는 구조가 겹쳐 비밀번호 부재 단언이 순서에 따라 실패했다. 로그는 클래스 이름만 남기고, 단언은 그 테스트가 낸 출력만 본다.
- 리뷰(resilientpos-multi-review, Claude·Codex 실행, Gemini는 CLI가 없어 미실행): Claude 82점, Codex는 변경 요청이었고 P0은 없었다.
  - 둘이 함께 지적한 항목은 반영했다. 비교 뒤에 세던 실패 카운터(동시 시도 통과)와 INCR·EXPIRE 분리(영구 잠금), 세션 쿠키 속성을 바인딩만 보던 테스트, 결과 절과 V015 대조가 그것이다.
  - 한쪽만 지적한 항목 중 반영한 것: unique 상세의 이메일 로그, `__Host-` 쿠키, 제어·서식 문자, CSRF 거절 로그의 토큰, Boot 기본 사용자, 체인과 Controller의 CSRF 저장소 공유, 쓰이지 않는 코드, Redis 주석, spec 문구(의존 방향, 인수 3·4, 질문 2·6·9).
  - 반영하지 않은 것: 제약 이름 판정의 locale 의존은 PostgreSQL 번역 메시지도 제약 이름을 큰따옴표로 감싸고 앞 리뷰에서 다뤘다. Redis health는 켜면 단말 핫패스까지 막혀 문서로 대신했다. Facade가 platform 예외를 던지는 것은 사용처가 하나라 두었다. 세션 절대 수명과 NFKC는 제외 절로 보냈다. bcrypt CPU 고갈은 rate limit 제외 항목에 근거로 적었다.
