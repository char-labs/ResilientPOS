# PR spec: 운영자 인증과 단말 등록·회전·비활성화 HTTP 입구를 연다

## 목적
단말 인증([terminal-authentication](2026-10-05-terminal-authentication.md))은 들어왔지만, 단말을 등록·회전·비활성화하는 입구가 `TerminalManagement` Facade뿐이다. 배포 환경에서는 단말을 등록할 방법이 없어서 API를 외부에 노출하지 못한다. 그 입구를 지킬 운영자 인증을 정하고, 단말 관리 HTTP API를 연다(이슈 #86).

기대 결과:
- `/admin/**` 경로는 OIDC IdP가 발급한 JWT로 인증하고 `OPERATOR` 역할을 요구한다.
- 운영자는 HTTP로 단말을 등록하고, credential을 회전하고, 비활성화하고, 매장별 단말 목록을 본다. 평문 토큰은 등록·회전 응답에 한 번만 실린다.
- 단말에 매장 안에서 유일한 `TerminalCode`가 생기고 기존 행은 채워진다.

## 범위
소유 모듈은 `terminal`(단말 코드, 관리 API, 목록 조회)과 `platform`(운영자 인증 체인, 401·403 응답)이다. 참고 문서의 근거는 시스템 설계의 "운영 API(reconciliation, replay)는 별도 role/scope 및 audit를 요구한다", PRD PR-05와 청사진의 "operator action에는 principal … 을 남긴다", 청사진 §2 `terminal` 행의 "매장 안에서 코드 유일"이다.

**결정 1: 운영자 인증은 Spring Security OAuth2 Resource Server(OIDC JWT)로 한다.** [terminal-authentication](2026-10-05-terminal-authentication.md) 결정 1("Spring Security를 들이지 않는다. 두 번째 주체가 생기면 다시 본다")을 운영자 주체에 한해 다시 정한다.
- 문제: 단말 관리 API를 부르는 사람이 운영자인지 서버가 모른다. 후속 운영 API(DLT 재투입, 운영자 재결제)도 같은 주체를 쓴다.
- 수단: 운영자의 계정·비밀번호·발급·폐기는 IdP가 전담하고 앱은 운영자를 저장하지 않는다. 앱은 서명, `exp`, `iss`, `aud`(`resilientpos-admin`)를 검사한다. 검증은 Spring Boot 자동 구성(`spring.security.oauth2.resourceserver.jwt.issuer-uri`, `audiences`, `authorities-claim-name`, `authority-prefix`)만으로 하고 `JwtDecoder`를 직접 만들지 않는다. `issuer-uri`의 디코더는 첫 요청 때 discovery를 하므로 IdP가 없어도 앱은 뜬다. 값은 환경 변수 `OPERATOR_ISSUER_URI`로 받는다.
- 역할은 top-level `roles` claim의 `OPERATOR` 하나다(`ROLE_OPERATOR`). 이 역할은 모든 매장의 단말을 다룬다. 지금 운영자를 기다리는 호출자(단말 관리, DLT 재투입, 운영자 재결제)가 모두 매장을 넘나드는 작업이라 매장 단위 권한은 두지 않는다.
- 폐기의 한계: JWT는 만료 전에 폐기가 반영되지 않는다. realm의 access token 수명을 5분으로 두어, IdP에서 비활성화한 운영자도 최대 5분 동안 통과한다. 단말 토큰과 달리 받아들이는 이유는 운영자가 사람이고 토큰을 매번 새로 받기 때문이다. 앱 쪽 폐기 목록과 introspection은 정본이 둘로 나뉘거나 요청마다 IdP를 부르게 되어 두지 않는다.
- 버린 것: 설정에 둔 정적 토큰은 발급·폐기가 재배포라서 버렸다. DB 운영자 계정과 비밀번호는 비밀번호 정책·잠금·세션이 따라와서 버렸다.

**결정 2: Security 체인은 `/admin/**`만 맡는다. 단말 인증은 지금의 인터셉터로 둔다.**
- `platform`의 `SecurityFilterChain` 하나가 `securityMatcher("/admin/**")`로 운영자 경로만 잡는다. 다른 경로는 어떤 체인과도 맞지 않아 체인의 필터(인증·인가)를 지나지 않는다. 다만 `FilterChainProxy`의 `StrictHttpFirewall`은 체인을 고르기 전에 모든 요청에 걸려서 경로에 `;`·인코딩된 `/`·`//`가 있거나 헤더에 제어 문자가 있으면 거절한다(리뷰 반영). 단말이 보내는 경로는 UUID와 고정 문자열이라 걸리지 않는다. 그래서 `/v1/**`(단말 인터셉터), `/actuator/**`, 시연 정적 자원은 지금과 같게 동작한다. Actuator는 지금처럼 배포(ingress)에서 막는다.
- 체인은 stateless이고 CSRF를 끈다. 쿠키·세션이 없고 Bearer 헤더만 받기 때문이다.
- 인증 수단이 둘(단말 인터셉터, 운영자 JWT)로 나뉘는 대가를 치른다. 단말 인증을 Security로 옮기면 #81의 401 계약을 Security 쪽에서 다시 만들고 기존 슬라이스 테스트 전반을 고쳐야 해서 이 PR에 넣지 않는다(제외).

**결정 3: 오류 응답은 기존 envelope를 쓴다.**
- `AuthenticationEntryPoint`와 `AccessDeniedHandler`는 응답을 직접 쓰지 않고 `HandlerExceptionResolver`에 예외를 넘겨 `ApiControllerAdvice`가 envelope와 로그를 맡게 한다.
- 토큰 없음, 서명·만료·`iss`·`aud` 오류는 모두 단말과 같은 `401 UNAUTHENTICATED`와 `WWW-Authenticate: Bearer`다. RFC 6750의 `error="invalid_token"` 상세는 싣지 않는다. 원인을 나누면 추측에 단서를 준다.
- 유효한 토큰이지만 `OPERATOR` 역할이 없으면 `403 ACCESS_DENIED`다. 업무 규칙이 아니라 HTTP 공통 오류라 `ErrorType`에 더한다. 단말의 `TERMINAL_MISMATCH`(`DomainException`)와 다르다.

**결정 4: 단말 관리 API.** Controller는 `terminal.internal.adapter.web`에 두고 `@AuthenticationPrincipal Jwt`를 받는다. 운영자 타입은 두 번째 호출자 모듈이 생길 때 만든다.
- `POST /admin/v1/terminals` 본문 `{storeId, terminalCode}` → `201 {terminalId, terminalCode, token}`.
- `POST /admin/v1/terminals/{terminalId}/credential/rotate` → `200 {token}`.
- `POST /admin/v1/terminals/{terminalId}/deactivate` → `200 {terminalId, status}`. 이미 비활성이면 같은 응답이다(멱등).
- `GET /admin/v1/stores/{storeId}/terminals` → `200 [{terminalId, terminalCode, status}]`, `terminalCode` 오름차순. 매장 하나의 단말 수는 한정적이라 페이지네이션은 두지 않는다. 쓰기 Facade와 나눈 `TerminalQueryFacade`와 `persistence/query`의 `TerminalQueries`(Kotlin JDSL)로 읽는다([payment-status-query](2026-10-06-payment-status-query.md)의 읽기 규약).
- 평문 토큰은 등록·회전 응답에만 있다. 응답 DTO의 `toString`은 토큰을 가린다.
- 기존 오류는 그대로다. 모르는 단말은 `404 TERMINAL_NOT_FOUND`, 비활성 단말의 회전은 `409 TERMINAL_INACTIVE`다.
- 회전 유예는 두지 않는다([terminal-authentication](2026-10-05-terminal-authentication.md) 결정 3 유지). 옛 토큰은 회전 커밋부터 401이고 단말은 새 토큰을 설치할 때까지 오프라인 큐를 보관한다.
- 비활성 단말의 미전송 큐는 받지 않는다. 비활성화는 분실·도난 대응이고 "비활성 단말은 인증 불가"가 불변식이다. 큐를 살려야 하는 교체라면 비활성화가 아니라 회전을 쓴다고 REST Docs 설명과 wiki에 적는다.

**결정 5: `TerminalCode`.**
- 운영자가 등록할 때 준다. 형식은 영대문자·숫자·하이픈 1~32자(`^[A-Z0-9-]{1,32}$`)이고 소문자를 대문자로 바꾸지 않고 `400 INVALID_TERMINAL_CODE`로 거절한다. 요청과 응답의 값이 같게 하려는 것이다. 값 객체 `TerminalCode`가 형식을 검사한다.
- 매장 안에서 유일하다. 마지막 방어선은 unique `(store_id, terminal_code)`이고 사전 조회는 하지 않는다. 위반은 persistence adapter가 제약 이름으로 가려 `409 TERMINAL_CODE_DUPLICATED`(`DomainException`, `CONFLICT`)로 바꾼다. 같은 코드의 동시 등록도 같은 경로를 탄다.
- 코드는 바뀌지 않는다. 바꾸는 명령은 두지 않는다.
- 기존 행은 `LEGACY-` + 매장별 `created_at, id` 순번으로 채운다(스키마 변경 검토).

**결정 6: 감사 로그.** Facade가 커밋한 뒤 Controller가 INFO 한 줄을 남긴다. 항목은 `operatorSub`(JWT `sub`), `operatorName`(`preferred_username`, 없으면 생략), `action`(`TERMINAL_REGISTERED`·`TERMINAL_CREDENTIAL_ROTATED`·`TERMINAL_DEACTIVATED`), `terminalId`, 그리고 등록이면 `storeId`·`terminalCode`, 비활성화면 전후 `status`다. 토큰과 `Authorization` 헤더는 남기지 않는다. 사유·증거를 담는 감사 표는 그것이 필요한 DLT 재투입·reconciliation PR이 만든다.

계약: 공개 타입 `TerminalCode`, `TerminalQuery`, `TerminalView`, `TerminalQueryFacade`가 생긴다. `TerminalStatus`는 `TerminalView`가 담아야 해서 `terminal.internal.domain`에서 모듈 루트로 옮긴다. `TerminalCommand.Register`가 `terminalCode`를 받고 결과에 담는다. `deactivate`는 전후 상태를 담은 `TerminalCommand.Deactivate.Result`를 돌려준다. 새 오류 code는 `ACCESS_DENIED`(`ErrorType`), `INVALID_TERMINAL_CODE`, `TERMINAL_CODE_DUPLICATED`다. 이벤트와 `event_publication`은 바뀌지 않는다.

불변식: 운영자 경로는 `OPERATOR` 역할 없이 부를 수 없다. 매장 안에서 단말 코드는 하나다. 평문 토큰은 저장하지 않고 응답에 한 번만 나간다. 그 밖의 단말 불변식(유효 credential 하나, 비활성 단말 인증 불가)은 기존 행 잠금과 partial unique가 그대로 지킨다.

복구: 등록 응답을 잃으면 그 단말은 토큰을 모르므로 목록에서 `terminalId`를 찾아 회전한다. 회전 응답을 잃으면 다시 회전한다. 비활성화는 멱등이다. 코드 중복 409는 아무것도 바꾸지 않는다.

관측성: 감사 INFO 로그(결정 6)와 advice의 기존 WARN 로그(401·403·409)를 쓴다. 새 metric은 없다.

## 제외
- DLT 재투입 API([kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md) 제외 절)와 운영자 재결제 입구([payment-status-query](2026-10-06-payment-status-query.md) 제외 절). 이 PR의 운영자 체인 위에 각 PR이 연다.
- 매장 운영자 권한과 작업별 scope. 결정 1.
- 운영자 토큰의 앱 쪽 폐기 목록·introspection. 결정 1.
- 단말 인증의 Spring Security 이관. 결정 2.
- 감사 표. 결정 6.
- 회전 유예, 재활성화, 비활성 단말의 큐 드레인. 결정 4.
- 단말 코드 변경 명령.
- Testcontainers로 Keycloak을 띄우는 테스트. Keycloak 연동은 compose로 수동 확인한다.
- rate limit, `store` aggregate와 매장 존재 확인([terminal-authentication](2026-10-05-terminal-authentication.md) 제외 절과 같다).
- 시연 화면(`static/terminal`)이 관리 API를 부르는 것.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 앞 PR은 [terminal-authentication](2026-10-05-terminal-authentication.md), [payment-status-query](2026-10-06-payment-status-query.md)다.

## 변경 경로
- build.gradle.kts
- docker/**
- src/main/kotlin/com/resilientpos/terminal/**
- src/main/kotlin/com/resilientpos/platform/**
- src/main/resources/application.yaml
- src/main/resources/db/migration/V014__terminal_code.sql
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/observability.md
- docs/wiki/entity-map.md
- docs/spec/2026-10-06-operator-authentication.md

## 인수 조건
1. `/admin/**`는 토큰이 없거나, 서명이 다르거나, 만료됐거나, `iss`·`aud`가 다르면 `401 UNAUTHENTICATED`와 `WWW-Authenticate: Bearer`이고 Facade를 부르지 않는다. 유효하지만 `OPERATOR` 역할이 없으면 `403 ACCESS_DENIED`다.
2. `/v1/**`의 단말 인증, `/actuator/health`, 시연 정적 자원의 동작은 바뀌지 않는다. 단말 토큰으로 `/admin/**`를 부르면 401이고, 운영자 JWT로 `/v1/**`를 부르면 401이다.
3. `POST /admin/v1/terminals`는 `ACTIVE` 단말과 credential을 만들고 `201 {terminalId, terminalCode, token}`을 돌려준다. 그 토큰으로 `/v1/**`를 인증할 수 있다.
4. 같은 매장에 같은 코드로 등록하면 `409 TERMINAL_CODE_DUPLICATED`이고 단말이 늘지 않는다. 다른 매장은 같은 코드를 쓸 수 있다. 형식이 틀린 코드(소문자 포함)는 `400 INVALID_TERMINAL_CODE`다.
5. 회전은 `200 {token}`이고 새 토큰만 인증된다. 비활성 단말은 `409 TERMINAL_INACTIVE`, 모르는 단말은 `404 TERMINAL_NOT_FOUND`다.
6. 비활성화는 `200 {terminalId, status: INACTIVE}`이고 다시 불러도 같다. 그 뒤 그 단말의 토큰은 401이다.
7. `GET /admin/v1/stores/{storeId}/terminals`는 그 매장의 단말만 코드 순으로 돌려준다. 토큰은 싣지 않는다.
8. 등록·회전·비활성화는 커밋 뒤 감사 INFO 로그를 한 줄 남기고, 로그와 응답 DTO의 `toString` 어디에도 평문 토큰이 없다.
9. V014가 기존 행을 매장별 `LEGACY-1`, `LEGACY-2`…로 채우고, 그 뒤 `terminal_code`는 NOT NULL이며 `(store_id, terminal_code)`가 unique다.
10. REST Docs 스니펫(`build/generated-snippets`)이 네 endpoint의 `Authorization` 헤더와 요청·응답 필드, 401·403·409 예를 문서화한다.
11. `ModuleStructureTest`가 통과한다.

### Checkpoint와 검증 명령 (선택)
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| 단말 코드와 마이그레이션 (4·9) | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest'` | 없음 |
| 운영자 체인 (1·2) | `./gradlew test --tests 'com.resilientpos.platform.*' --tests '*OperatorAuthentication*'` | 없음 |
| 관리 API·조회·감사 (3~8·10) | `./gradlew test --tests 'com.resilientpos.terminal.*'` | 위 둘 |

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

- 질문 1(도출): 도출할 수 없다. 운영자가 현장에서 부르는 이름이라 다른 컬럼에 없다. 기존 행의 `LEGACY-순번`은 채우기 위한 값이지 도출한 사실이 아니다.
- 질문 2(읽는 코드): 목록 조회가 `terminal_code`를 읽어 응답하고 정렬한다. 등록 결과도 돌려준다. 운영자가 코드로 단말을 찾아 `terminalId`를 얻는 경로가 이것이다. 인증·회전·비활성화도 `TerminalEntity.toDomain()`에서 `TerminalCode`를 만들며 읽는다. 그래서 수동 수정으로 형식에 맞지 않는 코드가 들어가면 그 단말의 `/v1` 인증이 401이 아니라 400 `INVALID_TERMINAL_CODE`가 된다. 코드를 쓰는 경로는 등록 하나이고 형식을 검사하므로 받아들인다(리뷰 반영).
- 질문 3(나중에 채우기): 지금 읽으므로 해당 없다. 기존 행은 위 UPDATE로 채운다.
- 질문 4(마지막 방어선): `ux_terminal_store_code`가 "매장 안에서 코드 유일"의 유일한 방어선이다. 사전 조회를 하지 않으므로 순차 등록과 동시 등록 모두 이 제약에서 걸린다. soft delete된 행에도 걸린다. 단말을 지우는 명령이 없고, 지운 단말의 코드를 재사용하게 하는 요구도 없다. 그런 명령이 생기면 그 PR이 partial unique로 바꿀지 정한다. 이 제약의 인덱스는 `store_id`가 앞이라 매장별 목록 조회도 탄다.
- 질문 5(정본): 정본은 `t_terminal.terminal_code` 하나다. 캐시하지 않는다.
- 질문 6(check-then-act): 등록은 읽고 판단하지 않고 INSERT만 한다. 경합은 unique가 막고 adapter가 409로 바꾼다. 기존 회전·비활성화는 코드를 쓰지 않는다.
- 질문 7(락): `ALTER TABLE … ADD COLUMN`(nullable, 기본값 없음)은 메타데이터 변경이지만 `ACCESS EXCLUSIVE`를 잡고, V014가 한 트랜잭션이라 그 락이 커밋까지 남는다. `SET NOT NULL`의 전체 검사와 `ADD CONSTRAINT UNIQUE`(역시 `ACCESS EXCLUSIVE`)는 행 수에 비례한다. 그동안 `t_terminal`을 읽는 단말 인증이 기다린다. 배포 환경에는 등록 입구가 없어 행이 없거나 몇 개뿐이라 즉시 끝나므로 `CONCURRENTLY`로 나누지 않는다. 긴 트랜잭션 뒤에서 락을 기다리며 인증을 세우지 않도록 첫 문장 앞에 `set local lock_timeout = '5s'`를 둔다(V005·V008·V011과 같다). 제약을 걸기 전 위반 행은 0건이다. `row_number()`가 매장별로 서로 다른 순번을 주고 UPDATE가 모든 행(삭제 행 포함)을 채우기 때문이다.
- 질문 8(이전 버전): 이전 버전은 새 컬럼을 매핑하지 않아 `validate`가 통과하고 인증·회전·비활성화는 그대로 동작한다. 이전 버전의 `register`는 `terminal_code` 없이 INSERT해 NOT NULL에 걸린다. 이전 버전에는 HTTP 등록 입구가 없고 main 코드에 Facade 호출자도 없어 받아들인다.
- 질문 9(payload): 이벤트 payload와 캐시되는 DTO에 실리지 않는다.
- 대조 기록: code-reviewer(읽기 전용 에이전트, 구현자 아님), 2026-10-06. V014__terminal_code.sql, TerminalEntity, TerminalPersistenceAdapter, TerminalQueries, TerminalManagement, Terminal, SchemaMigrationTest를 질문 1~9의 답과 대조했다. 불일치 2건(질문 7: V014에 `lock_timeout`이 없었고 unique 추가의 락을 `SHARE`로 적었으며 위반 행 확인이 없었다, 질문 2: 인증·회전·비활성화의 `toDomain` 읽기가 빠졌다)을 받아 V014와 위 답에 반영했다. 기존 마이그레이션 무변경은 리뷰어 도구에 git이 없어 구현자가 `git diff 2f79976..HEAD --stat -- src/main/resources/db/migration`(V014 신규 하나)으로 확인했다.

## 검증
- `./gradlew test`. 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다. 운영자 JWT는 테스트 리소스의 RSA 키 쌍으로 서명하고, 테스트는 운영과 같은 `issuer-uri`·`audiences` 설정을 그대로 쓰고 서명 키만 `jwk-set-uri`(테스트 코드의 로컬 JWKS 서버)로 받아 운영과 같은 검증기(issuer·audience·만료)를 탄다. `public-key-location`은 `issuer-uri`가 함께 있으면 Boot가 무시한다(`KeyValueCondition`).
- 정상: 운영자 JWT로 등록한 단말의 토큰으로 주문을 만든다. 회전 뒤 새 토큰만 통과한다. 비활성화 뒤 401이다. 목록이 코드 순이다.
- 실패: 토큰 없음·서명 다름·만료·`iss`·`aud` 불일치는 401, 역할 없음은 403, 코드 중복 409, 형식 오류 400, 모르는 단말 404, 비활성 단말 회전 409.
- 수동: `docker compose -f docker/docker-compose.yaml up -d keycloak`으로 realm을 띄워 토큰을 받고 `OPERATOR_ISSUER_URI`로 앱을 띄워 등록 API를 한 번 부른다. 결과는 결과 절에 남긴다.
- `python3 scripts/check_pr.py --base 2f79976 --worktree`.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): 운영자 인증(`/admin/**`, OIDC JWT, `OPERATOR`), 단말 관리 API, 단말 코드, `ACCESS_DENIED`를 적는다. "HTTP 등록 API가 없다 … 외부 노출은 여전히 하지 않는다"를 고친다. 외부 노출의 남은 조건(IdP 배포, ingress에서 Actuator 차단)을 적는다. `ErrorType` 항목 수를 고친다.
- [도메인 모델](../wiki/domain-model.md): `terminal` 행의 "매장 안에서 코드 유일(아직 구현하지 않았다)"을 구현됨으로 고치고, §4.7 사례에 `ux_terminal_store_code`를 적는다. 용어 `operator`가 용어집에 없으면 더한다.
- [관측성](../wiki/observability.md): 운영자 감사 로그 항목과 토큰 미기록을 적는다.
- [엔티티 지도](../wiki/entity-map.md): `TerminalEntity`의 `terminalCode`를 더한다.
- 요구사항 변화: 참고 문서의 요구(운영 API의 별도 권한, 운영자 행위의 실행자 기록, 매장 안 단말 코드 유일)를 처음 구현한다. 요구 자체는 바뀌지 않는다. [terminal-authentication](2026-10-05-terminal-authentication.md) 결정 1·2·4는 이 PR이 대체한다.

## 분할 계획
개발 컨텍스트는 하나다. 운영자가 누구인지 확인하고 그 운영자가 단말을 관리하게 하는 일이고, 그것이 요구하는 운영자 체인, 관리 API, 운영자가 단말을 고를 단말 코드가 딸려 온다. DLT 재투입·운영자 재결제는 같은 체인 위의 다른 업무라 각자의 PR로 뺐다. 작업 시작 시점(origin/main 2f79976)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 4·9, `TerminalCode` 형식과 V014 채우기, 코드 중복 409 (`TerminalCodeTest`, `TerminalManagementIntegrationTest`의 같은 매장 중복·다른 매장 허용, `SchemaMigrationTest`의 V014 채우기·baseline) | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest'`. 형식 검사 없는 `TerminalCode`와 미매핑 상태에서 5건 실패: `TerminalCodeTest` 거절 사례 assertion 실패, 중복 등록이 예외 없이 성공해 `assertFailsWith` 실패, baseline 버전 목록에 014 없음, V014 채우기는 V013 적용·행 삽입 뒤 `No migration with a target version 014`(V014 파일 없음), 다른 매장 허용은 `terminal_code` 컬럼 없음 SQL 오류 | 같은 명령 통과(BUILD SUCCESSFUL). V014는 spec SQL 그대로, `TerminalCode` 정규식 검사, adapter가 persist 직후 flush해 `ConstraintViolationException`의 제약명 `ux_terminal_store_code`만 `TERMINAL_CODE_DUPLICATED`(CONFLICT)로 변환 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest'` 통과, `./gradlew test` 전체 통과 |
| 인수 1·2, 운영자 체인의 401·403과 다른 경로 무영향 (`OperatorAuthenticationIntegrationTest` 10건: 토큰 없음·다른 키·만료·iss·aud 401, 역할 없음 403, OPERATOR 404 envelope, 단말 토큰 admin 401, 운영자 JWT v1 401, actuator health 200) | `./gradlew test --tests '*OperatorAuthenticationIntegrationTest'`. 의존성과 `ErrorType.ACCESS_DENIED` 선언만 있고 체인·설정이 없는 상태(Boot 기본 체인)에서 10건 중 8건 assertion 실패: 401 사례는 `WWW-Authenticate` 헤더·envelope 불일치, 403 사례는 ACCESS_DENIED envelope 불일치, actuator health는 200이 아님. 통과한 2건(OPERATOR 404, 운영자 JWT의 v1 401)은 기존 동작 characterization | 같은 명령 통과(10건). 체인 `OperatorSecurityFilterChain`, 401·403 advice 핸들러, application.yaml의 resourceserver 설정 추가. 첫 시도에서 yaml 누락으로 3건이 403이어서 설정을 넣은 뒤 통과 | `./gradlew test --tests ApiAdviceTest --tests ErrorLogRequestContextTest --tests '*ControllerTest' --tests '*InterceptorTest'`에서 슬라이스가 깨졌고(Boot 기본 체인이 v1을 막음) 체인을 `SecurityFilterChain` 타입 컴포넌트로 바꿔 통과. `./gradlew test` 전체 491건 통과 |
| 인수 3·5·6·10, 등록·회전·비활성화 endpoint와 REST Docs (`TerminalAdminControllerTest` 10건: 등록 201, 코드 형식 400, 중복 409, 회전 200·404·409, 비활성화 200 멱등·404, 목록 200, 경로 UUID 400) | `./gradlew test --tests '*TerminalAdminControllerTest'`. 핸들러 없이 컴파일용 빈 `TerminalAdminController`만 둔 상태에서 10건 모두 assertion 실패(`Status expected 201/200/409/400 but was 404`, `errorCode expected TERMINAL_NOT_FOUND but was NOT_FOUND`) | 같은 명령 통과. 컨트롤러 4 endpoint, 응답 DTO(토큰 `toString` 마스킹). 슬라이스의 운영자 인증은 `jwt()` post-processor(ROLE_OPERATOR)이고 `Authorization` 헤더는 REST Docs `preprocessRequest(modifyHeaders())`로 문서 예시에만 붙였다(헤더를 실제로 보내면 슬라이스에 `JwtDecoder`가 없어 500). 스니펫 9개가 `build/generated-snippets/terminals-*`에 생성됨 | `./gradlew test` 전체 505건 통과 |
| 인수 3·5·6·8, 운영자 JWT로 등록, 토큰으로 주문, 회전, 비활성화 멱등, 감사 로그 (`TerminalAdminApiIntegrationTest` 3건, `TerminalManagementIntegrationTest`의 비활성화 결과 전후 상태, `TerminalAdminDtosTest`) | `./gradlew test --tests '*TerminalAdminApiIntegrationTest' --tests '*TerminalAdminDtosTest' --tests '*OperatorAuthenticationIntegrationTest' --tests '*TerminalManagementIntegrationTest'`. 컨트롤러 없이 `deactivate`가 상태를 바꾸지 않는 컴파일용 스텁에서 25건 중 6건 실패: 통합 3건은 `/admin` 등록이 404(assertion), `OperatorAuthenticationIntegrationTest`의 OPERATOR 목록 200 사례는 404, `TerminalManagementIntegrationTest`의 비활성화 결과와 비활성 단말 회전 사례는 스텁이 비활성화하지 않아 실패. `TerminalAdminDtosTest`는 DTO를 먼저 선언해 바로 통과(characterization) | 같은 범위(`com.resilientpos.terminal.*`, `*OperatorAuthenticationIntegrationTest`, `*ModuleStructureTest`) 통과. `TerminalManagement.deactivate`가 `Deactivate.Result(terminalId, before, after)`를 돌려주고, 공개 `TerminalStatus`를 `terminal` 패키지로 옮겼다. 감사 로그는 Controller가 Facade 복귀 뒤 INFO 한 줄(`audit action=... operatorSub=... operatorName=... terminalId=...`)로 남기고, 로그 캡처로 평문 토큰(등록·회전) 부재를 확인 | `./gradlew test` 전체 505건 통과 |
| 인수 7, 매장별 목록 조회 (`TerminalAdminApiIntegrationTest`의 목록 단언, `TerminalAdminControllerTest`의 목록 200) | 위 통합 명령. 목록 `TerminalQueries`가 빈 목록을 돌려주는 스텁이라 통합 3건과 같은 실행에서 실패(등록 단계에서 먼저 404) | 같은 명령 통과. `TerminalQueries`가 JDSL로 `store_id`로 거르고 `terminal_code` 오름차순, `TerminalQueryFacade`가 위임. 다른 매장의 같은 코드(`POS-A`)가 섞이지 않음을 확인 | `./gradlew test` 전체 505건 통과 |
| 인수 10 보강, 401·403 REST Docs (`TerminalAdminControllerTest`의 `운영자 토큰이 없으면 401 UNAUTHENTICATED와 WWW-Authenticate이고 Facade를 부르지 않는다`·`OPERATOR 역할이 없으면 403 ACCESS_DENIED이고 Facade를 부르지 않는다`, 스니펫 `terminals-unauthenticated`·`terminals-access-denied`) | RED 미관측(characterization). 운영자 체인(인수 1·2)이 이미 들어와 있어 처음 실행부터 통과했다. 슬라이스는 토큰 없이 보내면 entry point를, 역할 없는 `jwt()`면 access denied handler를 타서 `JwtDecoder` 없이도 두 응답을 낸다 | `./gradlew test --tests '*TerminalAdminControllerTest'` 통과, `build/generated-snippets`에 두 스니펫 생성 확인 | `./gradlew test` 전체 507건 통과, 실패·건너뜀 0 |
| 리뷰 반영: V014 `lock_timeout`, 코드 중복 판정을 SQLState `23505`와 제약 이름으로, entry point의 401 원인 DEBUG 로그, import 순서, `toView` 확장 함수 | RED 미관측(characterization). Hibernate 7.4.5 `PostgreSQLDialect`의 제약 이름 추출기가 23505 메시지의 영어 템플릿(`constraint "…"`)을 파싱한다는 것을 sources jar로 확인했다. Testcontainers PostgreSQL은 영어 메시지라 기존 판정도 통과해서 실패를 재현하지 못했다. 서버 `lc_messages`를 바꾸는 테스트는 이미지의 NLS 지원을 확인하지 못해 두지 않았다. 기존 중복 409 통합 테스트가 회귀를 덮는다 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest'` 150건 통과 | `./gradlew test` 전체 507건 통과, 실패·건너뜀 0 |

## 결과
- `./gradlew test` 전체 507건 통과(실패 0, 건너뜀 0). 인수 조건 1~11을 구현했다. 각 동작의 RED·GREEN 관측은 TDD 검증 기록에 있다.
- 수동 확인(2026-10-06, compose의 Keycloak 26.4와 bootRun): realm의 `operator` 토큰은 `iss`가 `http://localhost:8180/realms/resilientpos`, `aud`에 `resilientpos-admin`, top-level `roles`에 `OPERATOR`, 수명 300초였다. 토큰 없이 목록을 부르면 401 `UNAUTHENTICATED`와 `WWW-Authenticate: Bearer`, 역할 없는 `viewer`는 403 `ACCESS_DENIED`였다. `operator`로 등록하면 201과 토큰을 받았고 그 토큰으로 빈 배치 sync가 200이었다. 목록은 그 단말을 보였고, 비활성화는 200 `INACTIVE`였으며 그 뒤 같은 토큰의 sync는 401이었다. 감사 로그는 등록·비활성화 두 줄이 남았고 로그 파일에서 평문 토큰은 0건이었다.
- 구현 중 판단:
  - Boot 4.1.1은 `issuer-uri`와 `public-key-location`을 함께 두면 public key 디코더를 만들지 않는다(`KeyValueCondition`). 그래서 테스트는 운영과 같은 `issuer-uri`·`audiences`를 두고 서명 키만 `jwk-set-uri`(테스트가 띄운 JDK `HttpServer`의 JWKS)로 받는다. issuer·audience 검증기는 `JwtDecoderConfiguration`이 `issuer-uri`·`audiences`가 있으면 붙인다(sources jar 확인).
  - 체인은 `@Bean`이 아니라 `SecurityFilterChain` 타입 컴포넌트다. `@WebMvcTest`가 이 타입만 슬라이스에 싣기 때문에, 의존성만 더했을 때 Boot 기본 체인이 슬라이스의 `/v1`을 막아 54건이 실패했다. 슬라이스에는 `JwtDecoder` 빈이 없어 디코더는 요청 때 `ObjectProvider`로 찾는다.
  - 감사 로그와 응답의 전후 상태를 위해 `deactivate`가 `Deactivate.Result`를 돌려주게 했고 `TerminalStatus`를 공개 패키지로 옮겼다(계약 절).
  - REST Docs의 `Authorization` 헤더는 슬라이스에서 `jwt()` post-processor로 인증한 뒤 요청 전처리로 문서 예시에만 붙인다. 슬라이스에서 실제 헤더를 보내면 디코더가 없어 500이다.
- 한계:
  - `OPERATOR_ISSUER_URI`가 닿지 않으면 운영자 요청은 envelope 없는 500이다(수동 확인). discovery 실패가 `AuthenticationServiceException`으로 다시 던져져 entry point와 advice를 거치지 않는다. 요청은 닫히고(fail-closed) 원인이 서버 설정이라 401로 바꾸지 않았다.
  - Spring Security의 `JwtTimestampValidator`가 `exp` 없는 토큰을 거절하는지는 고정하지 않았다. 서명이 IdP의 것이어야 하고 Keycloak은 항상 `exp`를 넣는다.
  - 목록 정렬은 DB collation을 따르는 사전순이라 `LEGACY-10`이 `LEGACY-2`보다 앞에 온다.
- 리뷰(code-reviewer, 읽기 전용): P0·P1은 없었다. P2 셋 중 V014 `lock_timeout`, 제약 이름의 locale 의존은 고쳤고, 모든 요청에 걸리는 firewall은 결정 2와 wiki 문구로 바로잡았다. P3 중 401 원인 로그, spec 질문 2·7·계약 문구, import 순서, `toView`는 반영했다. 동시 등록 테스트, 다른 제약 재던짐 분기 테스트, POST 경로의 401 사례는 두지 않았다. 동시 등록은 순차 중복과 같은 unique 경로이고, 체인은 URL 전체에 걸린다.
