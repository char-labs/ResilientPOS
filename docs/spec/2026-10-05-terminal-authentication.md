# PR spec: 단말 인증과 주문의 매장·단말 소유권 검사를 들인다

## 목적
쓰기 API 세 개(`POST /v1/orders`, `POST /v1/orders/{orderId}/checkout`, `POST /v1/terminals/{terminalId}/sync`)에 인증이 없다. `orderId`만 알면 다른 매장의 주문도 `CHECKOUT_REQUESTED`로 전이시킬 수 있고, 되돌리는 endpoint가 없어 복구할 수도 없다. 현재 상태(`docs/wiki/current-state.md`, 볼트)는 단말 인증과 소유권 검사가 들어오기 전에는 이 API를 외부에 노출하지 않는다고 적고, 인증만 넣으면 빈틈이 남는다고 경고한다. [order-request-checkout-api](2026-09-21-order-request-checkout-api.md), [sync-intake](2026-09-30-sync-intake.md), [sync-request-checkout](2026-09-30-sync-request-checkout.md)가 이 작업을 뒤로 미뤘다.

기대 결과:
- `terminal` 모듈에 `Terminal` aggregate와 credential 이력이 생기고, 공개 Facade `TerminalManagement`가 등록·회전·비활성화·인증을 맡는다.
- `/v1/**` 요청은 단말 토큰(`Authorization: Bearer`)이 없거나 유효하지 않으면 401이다.
- 인증된 단말은 자기 매장의 주문만 전이시키고, 요청에 실린 단말·매장 식별자가 자기와 다르면 거절된다.

## 범위
소유 모듈은 `terminal`(aggregate, 자격 증명, 인증 인터셉터)이다. `order`(checkout의 매장 검사), `sync`(경로·항목의 대조), `platform`(401 오류 매핑)이 딸려 바뀐다. 참고 문서의 근거는 청사진 §2의 `terminal` 행("inactive/retired terminal은 auth 불가; history 삭제 금지")과 §3.2의 sync 흐름("terminal auth + envelope validation"), 시스템 설계의 "object access는 tenant/store/principal ownership을 항상 검증한다"이다.

**결정 1: 단말별 불투명 토큰을 HandlerInterceptor가 검증한다. Spring Security는 들이지 않는다.**
- 문제: 요청한 단말이 누구인지 서버가 모른다.
- 제약: 인증 주체는 단말 하나뿐이고 역할·세션이 없다. 비활성 단말은 즉시 인증할 수 없어야 한다(도메인 모델 §2 `terminal` 불변식).
- 수단: 토큰은 `SecureRandom` 32바이트를 base64url(패딩 없음)로 표기한 문자열이다. DB에는 그 문자열의 SHA-256 hex만 저장하고 해시로 조회한다. 엔트로피가 256bit라 bcrypt 같은 느린 해시가 막을 대입 공격이 성립하지 않고, 해시로 바로 찾을 수 있어야 요청마다 행 하나를 읽고 끝난다. 조회 결과를 Redis에 캐시하지 않는다. 비활성화·회전이 즉시 반영돼야 하고 Redis는 정본이 될 수 없다(도메인 모델 §4.7 질문 4).
- JWT는 버렸다. 만료 전까지 폐기가 반영되지 않아 위 불변식이 깨진다. mTLS는 CA 운영과 ingress 설정이 필요하고 앱 테스트로 검증하기 어려워 버렸다. Spring Security는 의존성과 `SecurityFilterChain`·CSRF·세션 설정을 들이고 기존 테스트 전반에 인증 설정을 요구하는데, 인증 주체가 하나라 얻는 것이 없다. 운영자 인증처럼 두 번째 주체가 생기면 다시 본다.

**결정 2: 발급은 Facade만 한다. HTTP 등록 API는 없다.**
- `TerminalManagement.register(TerminalCommand.Register(storeId))`가 단말과 첫 credential을 한 트랜잭션에 넣고 평문 토큰을 결과로 한 번만 돌려준다. 평문은 저장하지 않는다.
- 등록 API를 열면 그 API를 지킬 운영자 인증이 또 필요하다. 이 PR의 인증 수단을 하나로 두려고 운영자 인증을 정하는 후속 PR로 미룬다. 그 전까지 배포 환경에서 단말을 등록할 입구가 없으므로 외부 노출은 여전히 하지 않는다.
- 매장 존재는 확인하지 않는다. `store` 모듈에 aggregate가 없다. 모듈 사이 FK도 두지 않는다.

**결정 3: 토큰은 만료되지 않는다. 폐기는 회전과 비활성화로 한다.**
- 오프라인 단말은 며칠 동안 큐를 쌓았다가 재연결할 수 있다. 만료가 있으면 재연결한 순간 sync가 401이 되어 큐를 비울 수 없다.
- `rotateCredential(TerminalCommand.RotateCredential(terminalId))`는 terminal 행을 잠그고(`PESSIMISTIC_WRITE`, 대기 3초) 유효 credential에 `revokedAt`을 찍은 뒤 새 credential을 넣고 새 평문 토큰을 돌려준다. 옛 토큰은 그 커밋부터 401이다. 유예 기간은 두지 않는다. 회전의 목적이 유출 대응이라 옛 토큰이 계속 통하면 목적을 잃는다. 새 토큰을 설치할 때까지 단말은 401을 받고 오프라인 큐를 보관한다(잃는 operation은 없다).
- `deactivate(TerminalCommand.Deactivate(terminalId))`는 같은 잠금 아래 상태를 `INACTIVE`로 바꾼다. 이미 비활성이면 그대로 돌려준다(멱등). 재활성화 명령은 없다. 다시 쓰려면 새 단말로 등록한다.
- 비활성 단말의 회전은 `TERMINAL_INACTIVE`(`CONFLICT`)로 거절한다. 모르는 단말은 `TERMINAL_NOT_FOUND`(`NOT_FOUND`)다.
- credential 이력은 지우지 않는다. 유효 credential은 단말당 하나이고 partial unique가 마지막 방어선이다(스키마 변경 검토).

**결정 4: `Terminal`은 `storeId`를 불변으로 든다. 단말 코드는 두지 않는다.**
- 매장을 옮기는 명령은 없다. 옮기려면 새 단말로 등록한다. watermark·주문이 `terminalId`에 묶여 있어 소속을 바꾸면 과거 주문의 매장이 흐려진다.
- 도메인 모델의 `TerminalCode`(매장 안에서 유일)는 읽는 코드가 없어 넣지 않는다(§4.7 질문 2). 운영자가 단말을 고르는 등록 API·조회 화면이 생길 때 넣고 기존 행은 그때 채운다.

**결정 5: 인증 경로.**
- `terminal.internal.adapter.web`의 인터셉터가 `/v1/**`에 걸린다. `Authorization` 헤더가 없거나 `Bearer ` 형식이 아니거나 `TerminalManagement.authenticate(token)`이 단말을 돌려주지 않으면 `platform`의 인증 실패 예외를 던진다. `authenticate`는 해시가 같고 폐기되지 않은 credential과 `ACTIVE` 단말을 한 조회로 찾아 `AuthenticatedTerminal(terminalId, storeId)`를 돌려준다.
- 인증 결과는 요청 속성에 두고, 같은 모듈의 argument resolver가 Controller 파라미터 `AuthenticatedTerminal`로 넘긴다. `AuthenticatedTerminal`은 `terminal` 모듈 루트의 공개 타입이다(§4.4). `order`·`sync`는 이미 `TerminalId`로 `terminal`에 의존하므로 새 간선이 아니다. 반대로 `terminal`은 리프 모듈이었는데 이제 `store`(값 타입 `StoreId`)와 `platform`·`foundation`(공용 인프라와 예외)에 의존한다. 모두 다른 모듈도 같은 방향으로 쓰는 공개 타입이라 순환이 생기지 않아 받아들인다(리뷰에서 바로잡음).
- `/actuator/**`는 경로가 달라 인터셉터 대상이 아니다. 지금처럼 배포(ingress)에서 외부로 라우팅하지 않는다.
- `Authorization` 헤더와 토큰은 로그에 남기지 않는다. `ApiControllerAdvice`는 method와 URI만 남긴다.

**결정 6: 소유권 검사.**
- `POST /v1/orders`: 본문의 `storeId`·`terminalId`가 인증 주체와 다르면 403 `TERMINAL_MISMATCH`다. API 계약은 바꾸지 않는다.
- `POST /v1/orders/{orderId}/checkout`: 주문의 `storeId`가 인증 주체의 매장과 같으면 다른 단말의 주문도 허용한다. 매장 안에서 다른 단말이 결제를 이어받는 흐름을 막지 않으려는 것이다. 다르면 주문이 없을 때와 같은 404 `ORDER_NOT_FOUND`다. 다른 매장 주문의 존재를 드러내지 않는다(UUID v7은 생성 시각이 드러나 추측 범위가 좁다). 운영자가 구분할 수 있게 매장 불일치는 WARN 로그(`orderId`·요청 `storeId`)로 남긴다. `OrderCommand.RequestCheckout`이 `storeId`를 받고 `OrderCommandFacade`가 잠근 주문과 대조한다.
- `POST /v1/terminals/{terminalId}/sync`: 경로의 `terminalId`가 인증 주체와 다르면 403 `TERMINAL_MISMATCH`다. `CREATE_ORDER` 항목 payload의 `storeId`가 인증 주체의 매장과 다르면 그 항목만 `REJECTED`+`STORE_MISMATCH`로 순번을 확정한다. 배치 전체를 거절하면 그 항목 하나 때문에 큐가 영구히 멈춘다. 판정은 기존 거절 경로(항목 트랜잭션 안의 `DomainException`)를 그대로 탄다. payload와 `payload_hash`는 바꾸지 않는다. `REQUEST_CHECKOUT` 항목은 `(terminalId, clientOrderId)`로 찾으므로 구조상 자기 단말의 주문이다.
- `SyncCommand.ReceiveBatch`가 인증 주체의 `storeId`를 받는다.

**결정 7: 오류 응답.**
- 401 `UNAUTHENTICATED`: 헤더 없음, 형식 오류, 모르는 토큰, 회전된 토큰, 비활성 단말이 모두 같은 code와 문구다. 원인을 나누면 토큰 추측에 단서를 준다. 인증은 업무 규칙이 아니라 HTTP 공통 오류라 `ErrorType`에 넣는다. 응답에 `WWW-Authenticate: Bearer`를 붙인다(RFC 6750).
- 403 `TERMINAL_MISMATCH`: `DomainException`이고 `DomainErrorKind.FORBIDDEN`을 새로 더한다. "요청자에게 그 대상을 다룰 권한이 없다"는 업무 의미다. advice의 `when`이 `else` 없이 매핑을 강제한다.
- sync 항목의 `STORE_MISMATCH`는 HTTP가 아니라 항목 결과다.

**관측성.** 401·403은 advice의 기존 WARN 로그(`errorCode`, method, URI)로 남는다. 새 metric은 없다. `terminalId`는 metric label로 쓰지 않는다([관측성](../wiki/observability.md)).

**범위 조정(PR #81 multi-review 반영).** Claude·Codex 리뷰가 지적한 것을 모두 이 PR에 넣는다.
- checkout은 주문 행을 잠근 뒤 매장을 비교했다. 다른 매장 주문이 그 매장의 요청으로 잠겨 있으면 최대 3초 뒤 409가 나서 즉시 404인 "없는 주문"과 구별되고, 다른 매장 단말이 남의 주문 행을 잠가 그 매장의 checkout·결제 반영을 기다리게 할 수 있었다. 잠금 조회 조건에 매장을 넣는다(`id`와 `store_id`로 잠금). 매장이 다른 행은 잠기지도 반환되지도 않아 없는 주문과 같다. WARN 로그는 null일 때만 잠그지 않는 존재 확인으로 구분하고, 요청한 단말 ID도 남긴다(`RequestCheckout`에 `terminalId`).
- 주문 생성은 본문 변환보다 소유권 대조를 먼저 한다. 변환 오류(400)보다 403이 앞선다.
- 인터셉터는 `Bearer` 스킴을 대소문자 구분 없이 받고(RFC 7235 §2.1), 형식이 토큰과 다른 값(base64url 43자가 아님)은 해시·DB 조회 없이 401이다. 응답은 같아 결정 7과 충돌하지 않는다.
- `UnauthenticatedException`은 스택 트레이스를 만들지 않는다. 대입 공격이 요청마다 스택 한 덩어리를 로그에 쌓지 않게 하려는 것이다.
- 비활성 단말의 회전 거절은 aggregate(`Terminal`)의 전제 조건으로 옮긴다. 해시 hex 변환은 `HexFormat`을 쓴다(출력 동일).
- credential은 `Terminal`의 자식이지만 루트가 목록으로 들지 않고 `TerminalManagement`가 따로 저장한다. 이력이 회전마다 늘어 루트를 읽을 때마다 싣지 않으려는 것이고, 회전은 루트 행 잠금 아래 한 커밋으로 묶어 자식 따로 저장 금지 규칙(domain-modeling §2)이 지키려는 원자성을 유지한다.
- 테스트를 더한다: 토큰이 로그와 결과 객체 `toString`에 남지 않음, 회전·비활성화 커밋 뒤 옛 토큰·비활성 단말이 401이고 새 토큰은 통과함, 비활성화가 먼저 커밋되면 겹친 회전은 `TERMINAL_INACTIVE`, V012의 두 unique를 직접 INSERT로 확인, checkout·sync의 401 HTTP 통합, 소문자 `bearer`와 빈 토큰.
- 리버스 프록시 access log의 `Authorization` 마스킹은 앱 밖 배포 설정이라 [관측성](../wiki/observability.md)의 배포 확인 항목으로만 적는다.

## 제외
- HTTP 단말 등록 API와 운영자 인증. 결정 2.
- 단말 코드(`TerminalCode`). 결정 4.
- 토큰 만료·갱신, 회전 유예 기간, 재활성화. 결정 3.
- 단말·매장 단위 rate limit(외부 연동 전략 §4의 inbound token bucket). 인증 주체가 생긴 뒤의 별도 컨텍스트다.
- DLT 재투입 API([kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)). 운영자 인증이 먼저 필요하다.
- 인증 실패 metric. 로그로 충분하고 대시보드 요구가 없다.
- `store` aggregate와 매장 존재 확인.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/terminal/**
- src/main/java/com/resilientpos/terminal/package-info.java
- src/main/kotlin/com/resilientpos/platform/ErrorType.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/UnauthenticatedException.kt
- src/main/kotlin/com/resilientpos/foundation/DomainException.kt
- src/main/kotlin/com/resilientpos/order/OrderCommand.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/web/**
- src/main/kotlin/com/resilientpos/sync/internal/**
- src/main/resources/db/migration/V012__terminal_credential.sql
- src/test/kotlin/com/resilientpos/**
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/observability.md
- docs/wiki/entity-map.md
- docs/spec/2026-10-05-terminal-authentication.md

## 인수 조건
1. `register`는 `ACTIVE` 단말과 credential 하나를 만들고 평문 토큰을 돌려준다. DB에는 토큰의 SHA-256 hex만 있고 평문은 없다.
2. `authenticate`는 유효한 토큰에 `AuthenticatedTerminal(terminalId, storeId)`를, 모르는 토큰·회전된 토큰·비활성 단말의 토큰에 없음을 돌려준다.
3. `rotateCredential` 뒤에는 새 토큰만 인증되고 옛 토큰은 인증되지 않는다. 옛 credential 행은 `revoked_at`을 가진 채 남는다. 같은 단말의 회전이 겹쳐도 유효 credential은 하나다.
4. `deactivate`는 멱등이고, 그 뒤 그 단말의 토큰은 인증되지 않는다. 비활성 단말의 회전은 `TERMINAL_INACTIVE`, 모르는 단말은 `TERMINAL_NOT_FOUND`다.
5. `/v1/**` 세 endpoint는 헤더가 없거나 `Bearer`가 아니거나 토큰이 유효하지 않으면 401 `UNAUTHENTICATED`와 `WWW-Authenticate: Bearer`를 돌려주고 Facade를 부르지 않는다. `/actuator/health`는 토큰 없이 200이다.
6. `POST /v1/orders`는 본문의 `storeId`나 `terminalId`가 인증 주체와 다르면 403 `TERMINAL_MISMATCH`이고 주문이 생기지 않는다. 같으면 지금과 같은 201이다.
7. checkout은 같은 매장의 다른 단말이 요청해도 200이다. 다른 매장 단말의 요청은 404 `ORDER_NOT_FOUND`이고 주문은 `OPEN`이며 checkout 행과 발행 기록이 없다.
8. sync는 경로의 `terminalId`가 인증 주체와 다르면 403 `TERMINAL_MISMATCH`이고 watermark를 만들지 않는다. `CREATE_ORDER` 항목의 `storeId`가 다르면 그 항목은 `REJECTED`+`STORE_MISMATCH`이고 순번은 확정되며 다음 항목은 적용된다. 재전송은 같은 거절의 `DUPLICATE`다.
9. REST Docs 스니펫의 요청에 `Authorization` 헤더가 문서화되고, 기존 Controller·통합 테스트는 인증 헤더를 붙여 지금과 같은 결과를 낸다.
10. `ModuleStructureTest`가 통과한다(순환 없음, 다른 모듈의 internal 접근 없음).

## 스키마 변경 검토
`V012__terminal_credential.sql`이 새 표 둘을 만든다. 기존 표는 바꾸지 않는다.

```sql
create table t_terminal (
    id uuid primary key,
    store_id uuid not null,
    status varchar(32) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create table t_terminal_credential (
    id uuid primary key,
    terminal_id uuid not null,
    token_hash varchar(64) not null,
    revoked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_terminal_credential_token_hash unique (token_hash)
);

create unique index ux_terminal_credential_active on t_terminal_credential (terminal_id) where revoked_at is null;
```

- 질문 1(도출): 도출할 수 없다. 단말과 매장의 소속, 상태, 토큰 해시는 지금 어디에도 없다. `t_order.store_id`·`terminal_id`는 주문의 snapshot이고 단말이 없는 매장 소속을 말하지 않는다.
- 질문 2(읽는 코드): 업무 컬럼은 모두 읽힌다. `t_terminal.store_id`는 인증 결과의 `storeId`가 되어 소유권 검사가, `status`는 인증과 회전이, `t_terminal_credential.terminal_id`는 인증의 조인과 회전의 유효 credential 조회가, `token_hash`는 인증이, `revoked_at`은 인증과 회전이 읽는다. 회전은 `token_hash`를 쓰기만 한다. `created_at`·`updated_at`은 `BaseEntity` 관례로 붙은 감사 컬럼이라 업무 코드가 읽지 않는다. 단말 코드는 읽는 코드가 없어 넣지 않았다(결정 4).
- 질문 3(나중에 채우기): 해당 없다. 넣는 컬럼은 모두 지금 읽힌다.
- 질문 4(마지막 방어선): `ux_terminal_credential_token_hash`는 해시 하나가 두 단말을 가리키지 않게 하는 방어선이다. 256bit 난수라 충돌은 사실상 없지만 조회가 한 행을 전제하므로 제약으로 둔다. `ux_terminal_credential_active`는 "유효 credential은 단말당 하나"의 방어선이다. 평소에는 terminal 행 잠금이 회전을 직렬화하고, 잠금 밖에서 들어온 행(수동 수정)을 여기서 막는다. 둘 다 soft delete된 행에도 걸린다. 지우는 명령이 없고 이력 삭제 금지가 불변식이다. 지우는 명령이 생기면 조회가 삭제된 행도 보게 해야 한다(`@SoftDelete`는 조회만 거르고 제약은 남는다). `terminal_id` 전체 인덱스는 두지 않는다. 지금 조회는 유효 행만 찾아 partial unique 인덱스로 충분하다. 단말의 credential 이력을 조회하는 코드가 생기면 그때 더한다.
- 질문 5(정본): 정본은 하나다. 단말의 매장은 `t_terminal.store_id`, 유효 토큰은 `revoked_at is null`인 행이다. 인증 결과를 캐시하지 않는다.
- 질문 6(check-then-act): 회전과 비활성화는 단말 상태를 읽고 쓴다. terminal 행 잠금(`PESSIMISTIC_WRITE`, 대기 3초)이 막고 Redis `Lock`과 함께 기다리지 않는다. 행 잠금이 있으므로 `@Version`은 두지 않는다(sync watermark와 같은 판단). 인증은 읽기만 한다. 인증과 회전이 겹치면 인증은 커밋 전 상태를 읽어 옛 토큰을 한 번 더 통과시킬 수 있다. 비활성화와 겹칠 때도 같아서 커밋 직전의 인증이 `ACTIVE`를 읽고 통과할 수 있다. 두 경우 모두 그 요청은 커밋 직전에 도착한 요청과 구별되지 않아 받아들인다. 회전은 terminal 잠금 조회와 유효 credential 조회를 따로 실행하지만, credential을 쓰는 경로가 등록(새 단말이라 경합이 없다)과 회전(같은 잠금 아래)뿐이라 READ COMMITTED에서도 두 조회가 서로 다른 커밋을 섞어 읽지 않는다. Hibernate가 한 flush에서 INSERT를 UPDATE보다 먼저 보내므로 회전은 옛 credential의 폐기 UPDATE를 먼저 flush한다. 그러지 않으면 새 유효 행이 partial unique에 걸린다(flush를 지운 변형에서 겹침 테스트가 `DataIntegrityViolationException`으로 실패하는 것을 관측했다).
- 질문 7(락): 새 표라 기존 표에 락을 잡지 않는다. 행이 없어 인덱스 빌드가 즉시 끝난다.
- 질문 8(이전 버전): 이전 버전은 새 표를 모르고 매핑하지 않아 `validate`가 통과한다. 이전 버전 인스턴스는 인증 없이 요청을 받으므로 배포가 끝날 때까지 빈틈이 남는다. 외부에 노출하지 않은 상태라 받아들인다. DDL은 기존 표를 바꾸지 않지만 기존 컬럼에 새 값이 들어간다. sync 항목 거절이 `t_sync_operation.error_code`(`varchar(64)`)에 `STORE_MISMATCH`를 기록한다. 엔티티가 `String`으로 매핑하므로 이전 버전이 그 행을 `DUPLICATE` 응답으로 읽어도 깨지지 않는다. checkout의 매장 대조는 이미 매핑된 `t_order.store_id`를 읽는다.
- 질문 9(payload): 이벤트 payload와 캐시되는 DTO에 실리지 않는다. 토큰 평문은 등록·회전 결과에만 있다.
- 대조 기록: code-reviewer(읽기 전용 에이전트), 2026-10-05. V012__terminal_credential.sql, V010__sync_intake.sql, TerminalEntity, TerminalCredentialEntity, TerminalPersistenceAdapter, Terminal, TerminalCredential, TerminalRepository, TerminalManagement, TerminalCommand, BaseEntity, JdslQueryExecutor, OrderCommandFacade, OrderEntity, SyncCommandFacade, SyncOperationEntity를 질문 1~9의 답과 대조했다. 기존 마이그레이션 무변경은 리뷰어가 git status로 보고 구현자가 `git diff 8133048 -- src/main/resources/db/migration`(V012 신규 하나)으로 다시 확인했다. 불일치 1건(질문 2의 읽는 컬럼 목록)과 누락 4건(질문 8의 `error_code` 새 값, 질문 6의 회전 두 조회의 일관성 근거와 비활성화의 같은 창, 질문 4의 `terminal_id` 전체 인덱스 부재)을 받아 위 답에 반영했다. 범위 밖 관찰 1건(adapter 쓰기 메서드의 `@Transactional` 안전망 누락)은 rules와 다른 adapter의 관례대로 고쳤다.

## 검증
- `./gradlew test`. 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다.
- 정상: 등록한 단말의 토큰으로 주문 생성·checkout·sync가 지금과 같은 결과를 낸다. 같은 매장 다른 단말의 checkout이 200이다.
- 실패: 토큰 없음·모르는 토큰·회전된 토큰·비활성 단말은 401, 식별자 불일치는 403, 다른 매장 주문 checkout은 404, sync 항목의 매장 불일치는 `REJECTED`.
- `python3 scripts/check_pr.py --base 8133048 --worktree`.

## Wiki 영향
- 현재 상태(`docs/wiki/current-state.md`, 볼트): `terminal` 모듈의 aggregate·Facade·인증 경로, checkout·sync의 소유권 검사, 401·403 응답을 적는다. "인가가 없다"와 sync의 "단말 인증 … 없다"를 고친다. 등록 API가 없어 외부 노출은 여전히 하지 않는다는 점과 이전 버전 인스턴스의 빈틈을 남긴다. `ErrorType` 항목 수를 고친다.
- [도메인 모델](../wiki/domain-model.md): `terminal` 행의 "매장 안에서 코드 유일"이 아직 구현되지 않았다는 점, `Terminal` 구성(credential 이력), §4.7 사례에 두 unique를 적는다. 용어 `credential`이 용어집에 없으면 더한다.
- [관측성](../wiki/observability.md): "앱에 인증이 없으므로" 문장을 `/v1/**`만 인증하고 Actuator는 배포에서 막는다로 고친다.
- [엔티티 지도](../wiki/entity-map.md): `TerminalEntity`·`TerminalCredentialEntity`의 역할과 참조 관계를 더한다. 새 엔티티의 KDoc이 이 문서를 가리킨다(범위 조정, 구현 중 추가).
- 요구사항 변화: 참고 문서의 요구(단말 인증, 소유권 검사, 비활성 단말 인증 불가)를 처음 구현한다. 요구 자체는 바뀌지 않는다.

## 분할 계획
개발 컨텍스트는 하나다. 단말이 누구인지 확인하고 그 단말이 자기 매장의 주문만 다루게 하는 일이고, 그것이 요구하는 `terminal` aggregate와 세 endpoint의 대조가 딸려 온다. HTTP 등록·운영자 인증과 rate limit은 주체가 다른 별도 컨텍스트라 뺐다. 작업 시작 시점(origin/main 8133048)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1~4, `TerminalManagement` 등록·인증·회전·비활성화. 통합 `TerminalManagementIntegrationTest`(등록하면 ACTIVE 단말과 credential 하나가 생기고 DB에는 토큰의 해시만 있다 / 유효한 토큰은 단말과 매장을 돌려주고 모르는 토큰은 없음이다 / 회전하면 새 토큰만 인증되고 옛 credential 행은 폐기된 채 남는다 / 같은 단말의 회전이 겹쳐도 유효 credential은 하나다 / 단말 행이 잠겨 있으면 회전은 잠금이 풀리기를 기다린다 / 비활성화는 멱등이고 그 단말의 토큰은 인증되지 않는다 / 비활성 단말의 회전은 TERMINAL_INACTIVE이고 credential이 늘지 않는다 / 모르는 단말의 회전과 비활성화는 TERMINAL_NOT_FOUND다), 도메인 `TerminalTest`·`TerminalCredentialTest` | `./gradlew test --tests 'com.resilientpos.terminal.*'`. 공개 타입·`TODO()` 본문 스텁만 둔 상태에서 14개 전부 실패했다. 이유는 `kotlin.NotImplementedError`(동작 미구현)이고 컴파일 오류가 아니다. 테이블이 없어서가 아니라 스텁에서 먼저 실패했으므로 마이그레이션 부재는 이 RED에 포함되지 않는다 | 같은 명령. 통합 8개와 도메인 6개 전부 통과. 구현 중 관측: 회전 중 옛 credential 폐기 UPDATE를 flush하지 않으면 Hibernate가 INSERT를 먼저 보내 `ux_terminal_credential_active`에 걸려 겹침 테스트가 `DataIntegrityViolationException`으로 실패했고, 폐기 직후 flush를 넣어 해소했다 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.EntityVisibilityTest' --tests 'com.resilientpos.TransactionDeclarationTest' --tests 'com.resilientpos.DependencyDirectionTest'`. 30개 통과, 실패 0 |
| 인수 5, 인터셉터의 401과 Actuator 제외. 슬라이스 `OrderControllerTest`(Authorization 헤더가 없으면 401 UNAUTHENTICATED와 WWW-Authenticate이고 Facade를 부르지 않는다 / Bearer 형식이 아니거나 authenticate가 없음을 돌려주면 같은 401이고 Facade를 부르지 않는다, REST Docs `orders-create-unauthenticated`), `OrderCheckoutControllerTest`·`SyncControllerTest`의 헤더 없음 401, 통합 `TerminalAuthenticationIntegrationTest`(헤더 없음 / Bearer 아님 / 모르는 토큰 / 회전된 옛 토큰 / 비활성 단말은 각각 같은 401이고 주문이 생기지 않는다, 유효한 토큰은 201, actuator health는 토큰 없이 200) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'`. 인터셉터·`UnauthenticatedException`·`UNAUTHENTICATED` 없이 새 401 슬라이스 2개가 assertion으로 실패했다(헤더가 없어도 요청이 통과해 401이 아니었다). 통합 `TerminalAuthenticationIntegrationTest`의 별도 RED는 관측하지 않았다(슬라이스 RED 뒤 구현과 함께 처음 실행) | 같은 명령 통과(15개). `./gradlew test --tests '*ControllerTest' --tests '*ApiIntegrationTest' --tests '*TerminalAuthenticationIntegrationTest'` 통과 | `./gradlew test` 전체. 397개 중 실패 0 |
| 인수 6, 주문 생성의 403. 슬라이스 `OrderControllerTest`(본문의 storeId나 terminalId가 인증 주체와 다르면 403 TERMINAL_MISMATCH이고 Facade를 부르지 않는다, REST Docs `orders-create-terminal-mismatch`), 같을 때 201은 기존 `유효한 요청은 본문을 생성 커맨드로 옮기고 201과 orderId·status를 돌려준다`와 `OrderApiIntegrationTest`가 고정한다. `AuthenticatedTerminal.requireSame`, `DomainErrorKind.FORBIDDEN`(`ApiAdviceTest`의 kind 매핑 표에 403 추가) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'`. 본문 비교 없는 `requireSame` 빈 본문 스텁과 FORBIDDEN 선언만 둔 상태에서 새 테스트 1개만 assertion으로 실패했다(불일치 요청이 403이 아니라 통과) | 같은 명령 통과(16개) | `./gradlew test` 전체. 400개 중 실패 0 |
| 인수 7, checkout의 같은 매장 허용과 다른 매장 404. 통합 `OrderRequestCheckoutIntegrationTest`(주문의 매장과 다른 매장의 requestCheckout은 ORDER_NOT_FOUND이고 주문은 OPEN이며 행도 이벤트도 없다), `OrderApiIntegrationTest`(같은 매장의 다른 단말은 checkout이 200이고 다른 매장의 단말은 404 ORDER_NOT_FOUND이며 주문은 OPEN으로 남는다. checkout 행과 발행 기록 0 확인), 슬라이스 `OrderCheckoutControllerTest`는 커맨드에 인증 주체의 `storeId`가 실리는지 고정 | `./gradlew test --tests '*OrderRequestCheckoutIntegrationTest' --tests '*OrderApiIntegrationTest'`. `RequestCheckout`에 `storeId`만 더하고 Facade 검사가 없는 상태에서 새 테스트 2개가 assertion으로 실패했다(다른 매장 요청이 CHECKOUT_REQUESTED로 전이되어 예외가 나지 않음). 같은 실행에서 기존 `IDEMPOTENCY_CONFLICT` 통합 테스트가 임의 storeId·terminalId 본문이 403이 되어 깨졌고 인증 주체의 식별자를 쓰도록 고쳤다 | 같은 명령과 `--tests '*ControllerTest'` 통과 | `./gradlew test` 전체. 400개 중 실패 0 |
| 인수 8, sync 경로 403과 항목 `STORE_MISMATCH`. 통합 `SyncCommandFacadeIntegrationTest`(인증된 단말의 매장과 다른 storeId의 주문 생성 항목은 STORE_MISMATCH 거절이고 순번은 확정되며 다음 항목은 적용되고 재전송은 같은 거절의 중복이다), 슬라이스 `SyncControllerTest`(경로의 terminalId가 인증된 단말과 다르면 403 TERMINAL_MISMATCH이고 Facade를 부르지 않는다, REST Docs `sync-receive-batch-terminal-mismatch`), 통합 `SyncApiIntegrationTest`(다른 단말의 경로로 보내면 403 TERMINAL_MISMATCH이고 watermark 행이 생기지 않는다) | `./gradlew test --tests 'com.resilientpos.sync.*'`. `ReceiveBatch`에 `storeId`만 더하고 대조는 두지 않은 상태에서 3개 실패했다. Facade는 기대 `[REJECTED, APPLIED]`인데 실제 `[APPLIED, APPLIED]`, 슬라이스는 403 기대에 500(대조가 없어 Facade 목이 null을 돌려줌), 통합은 403 기대에 200 | 같은 명령. 69개 전부 통과. `SyncController`의 `requireSame` 한 줄과 Facade의 `OrderPayload` 항목 storeId 대조를 넣었다. 기존 sync 테스트의 payload storeId는 이미 맞춰져 있어 기대 결과가 바뀐 것이 없다 | `./gradlew test` 전체 403개 통과, 실패 0. `python3 scripts/check_pr.py --base 8133048 --worktree` PASS |
| 인수 9, 기존 Controller·REST Docs·통합 테스트의 인증 헤더. `OrderControllerTest`·`OrderCheckoutControllerTest`·`SyncControllerTest`는 `TerminalManagement`를 mock해 고정 `AuthenticatedTerminal`을 돌려주고 `Authorization` 헤더를 붙이며 모든 `document`에 `requestHeaders`를 더했다. `OrderApiIntegrationTest`·`SyncApiIntegrationTest`는 실제 `register`한 단말의 토큰과 `terminalId`·`storeId`를 쓴다. `ApiAdviceTest`·`ErrorLogRequestContextTest`는 인터셉터 설정이 슬라이스에 실려 `TerminalManagement` mock만 더했다 | 인터셉터를 넣은 직후 기존 테스트 15개(`ApiAdviceTest` 9, `ErrorLogRequestContextTest` 6)가 `TerminalManagement` 빈이 없어 컨텍스트 로드 실패로 깨졌다(전체 실행에서 관측). Controller 슬라이스와 통합 테스트의 401 회귀는 헤더를 붙이는 수정을 인터셉터 구현과 같은 단계에서 해서 따로 관측하지 않았다 | 위와 같은 명령 통과, `build/generated-snippets`의 `orders-checkout` 등에 `request-headers.adoc` 생성 확인 | `./gradlew test` 전체. 397개 중 실패 0 |
| 리뷰 반영, `OrderRequestCheckoutIntegrationTest`의 `다른 매장의 요청은 주문 행이 잠겨 있어도 기다리지 않고 ORDER_NOT_FOUND를 받는다` | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest' --tests 'com.resilientpos.order.internal.application.OrderRequestCheckoutIntegrationTest'`. 다른 트랜잭션이 행을 잠근 동안 다른 매장 요청이 2초 안에 끝나지 않아 `TimeoutException`. 잠근 뒤 매장을 비교해 잠금을 3초 기다렸다 | 매장 조건 잠금 조회(`findByIdAndStoreIdForUpdate`) 뒤 같은 명령 33개 통과. 잠금 대기 세션 0도 단언한다 | `./gradlew test --tests 'com.resilientpos.order.*'` 123개 통과 |
| 리뷰 반영, `OrderControllerTest`의 `금액이 잘못된 본문도 소유권이 다르면 변환 오류 400보다 403 TERMINAL_MISMATCH가 먼저다` | 같은 명령. 403을 기대했는데 400(`unitPrice` 4500.5 KRW, 다른 매장 `storeId`) | 원시 `storeId`·`terminalId`로 먼저 대조한 뒤 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, `TerminalAuthenticationInterceptorTest`의 `Bearer 스킴은 대소문자를 구분하지 않는다`, 통합 `소문자 bearer 스킴도 유효한 토큰이면 통과한다` | `./gradlew test --tests '*TerminalAuthenticationInterceptorTest'`. 대소문자를 구분하는 변형에서 `Status expected 200 but was 401`(첫 시도의 500은 테스트 컨트롤러 반환형 문제라 RED로 치지 않았다) | 대소문자 무관 비교 뒤 통과 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.platform.*'` 106개 통과 |
| 리뷰 반영, `TerminalAuthenticationInterceptorTest`의 `형식이 토큰과 다른 값은 authenticate를 부르지 않고 같은 401이다`, 통합 `형식이 토큰과 다른 값은 401이고 주문이 생기지 않는다` | 같은 명령. `Bearer unknown-token`에서 `authenticate`가 불려 `verify(never())` 실패 | base64url 43자 검사 뒤 통과. fixture 토큰을 43자 값으로 바꿨고 `모르는 토큰` 사례는 형식이 맞는 미발급 토큰으로 DB 조회 경로를 계속 탄다 | 위와 같은 회귀 통과 |
| 리뷰 반영, `UnauthenticatedExceptionTest`의 `스택 트레이스를 만들지 않고 원인도 싣지 않는다` | `./gradlew test --tests '*UnauthenticatedExceptionTest'`. `stackTrace`가 비어 있지 않음 | 통과. `ApiAdviceTest`도 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, `TerminalTest`의 `활성 단말은 credential 발급 전제 조건을 통과한다`·`비활성 단말의 credential 발급 전제 조건은 TERMINAL_INACTIVE CONFLICT로 거절한다` | `./gradlew test --tests '*TerminalTest'`. 빈 `requireActive()`에서 `Expected DomainException ... completed successfully` | `when (status)`를 else 없이 둔 뒤 통과. 기존 회전 거절 통합 테스트가 회귀로 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, `TerminalCredentialTest`의 `해시는 고정 입력의 SHA-256 소문자 hex 64자다` | RED 미관측(characterization). `HexFormat`으로 바꾸기 전에 통과를 확인했다 | 바꾼 뒤에도 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, 통합 `토큰은 401 요청과 성공 요청 어디서도 출력에 남지 않고 401은 로그에 남는다`, `TerminalCommandTest`의 두 `toString` 테스트 | RED 미관측(characterization). 인터셉터에 토큰을 출력하는 변형과 `toString` 재정의를 지운 변형에서 각각 실패를 관측하고 되돌렸다 | 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, `TerminalManagementIntegrationTest`의 `비활성화가 먼저 커밋되면 잠금을 기다리던 회전은 TERMINAL_INACTIVE이고 credential이 늘지 않는다` | RED 미관측(characterization). `requireActive()`를 지운 변형에서 실패를 관측하고 되돌렸다. 회전·비활성화 커밋 뒤의 401은 기존 인수 5 통합 테스트가 덮는다 | 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, `TerminalCredentialSchemaIntegrationTest`의 `같은 token_hash 행 두 개는 unique 위반이다`·`같은 단말의 revoked_at이 null인 행 두 개는 unique 위반이다`·`폐기된 행은 같은 단말에 여러 개 넣을 수 있고 유효 행 하나와도 함께 둘 수 있다` | RED 미관측(characterization). 제약 이름까지 메시지로 확인한다 | 통과 | 위와 같은 회귀 통과 |
| 리뷰 반영, 통합 `checkout은 헤더가 없으면 401이고 checkout 행이 생기지 않는다`·`sync는 헤더가 없으면 401이고 watermark와 operation 행이 생기지 않는다` | RED 미관측(characterization) | 통과 | 위와 같은 회귀 통과 |

## 결과
- `./gradlew test` 전체 403개 통과(실패 0, 건너뜀 0). multi-review 반영 뒤 전체 422개 통과(실패 0, 건너뜀 0). `python3 scripts/check_pr.py --base 8133048 --worktree` PASS.
- 인수 1~10을 모두 구현했다. 각 동작의 RED·GREEN 관측은 TDD 검증 기록에 있다.
- 구현 중 판단:
  - 회전은 옛 credential의 폐기 UPDATE를 먼저 flush한다. Hibernate가 한 flush에서 INSERT를 먼저 보내 새 유효 행이 partial unique에 걸리기 때문이다(스키마 변경 검토 질문 6).
  - 등록·회전 결과 타입의 `toString`은 토큰을 가린다. 결과 객체가 로그에 찍혀도 평문이 남지 않게 하려는 것이다.
  - `@WebMvcTest` 슬라이스가 인증 인터셉터와 `WebMvcConfigurer`를 자동으로 싣는다. 그래서 `/v1`을 부르지 않는 슬라이스 테스트(`ApiAdviceTest`, `ErrorLogRequestContextTest`)도 `TerminalManagement` mock을 둔다.
  - 통합 401 사례는 `POST /v1/orders` 하나로 대표했다. 세 endpoint가 같은 인터셉터를 지나고, checkout·sync는 헤더 없음 슬라이스 테스트로 연결을 확인한다.
  - `AuthenticatedTerminal` argument resolver는 요청 속성이 없으면 null 대신 예외를 던진다. 인터셉터 밖 경로에서 쓰는 실수를 숨기지 않으려는 것이다.
- 범위 조정: 새 엔티티의 KDoc이 가리키는 [엔티티 지도](../wiki/entity-map.md)를 Wiki 영향과 변경 경로에 더했다. `current-state.md`는 볼트 링크라 git diff에 나오지 않는다.
- multi-review(Claude 86점, Codex 보안 90·정합성 91·모듈 경계 94·테스트 82, Gemini 미실행: CLI 없음): 두 리뷰어 모두 인증 우회와 토큰 평문 유출 경로를 찾지 못했다. 지적은 모두 범위 조정 절대로 반영했다. 리버스 프록시 로그 마스킹은 앱 밖이라 관측성 문서의 배포 확인 항목으로 남겼다. 같은 worktree에서 두 에이전트가 gradle을 동시에 돌려 build 디렉터리 충돌(`EOFException`·`NoSuchFileException`)이 났고, 이는 RED가 아니라 환경 오류라 재실행했다. 전체 실행은 하나만 돌려 확인했다.
