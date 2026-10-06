# PR spec: 결제 시도 조회 API와 재결제 endpoint를 연다

## 목적
endpoint가 쓰기 세 개뿐이라 단말이 결제가 거절됐는지, 결과가 불명인지 알 수 없다. 주문은 거절 뒤에도 `CHECKOUT_REQUESTED`에 머물러서([payment-unknown-resolution](2026-10-01-payment-unknown-resolution.md)) 주문 상태로는 알 길이 없다. 재결제 명령 `retryAuthorization`은 있지만([payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md)) HTTP 입구가 없고, 부르려면 거절된 시도의 `paymentId`를 알아야 한다. 단말이 주문의 결제 시도를 조회해 거절과 `paymentId`를 알고, 그 시도로 재결제를 요청하게 한다. 첫 조회 API이므로 README가 "첫 조회 PR에서 정한다"로 남긴 읽기 규약을 함께 확정한다(이슈 #75).

## 범위
소유 모듈은 `payment`다. `order`·`sync`·`terminal`·`contract`는 바꾸지 않는다. 두 endpoint 모두 `/v1/**` 단말 인증 인터셉터([terminal-authentication](2026-10-05-terminal-authentication.md)) 뒤에 있고 Controller 파라미터 `AuthenticatedTerminal`의 `storeId`로 소유권을 검사한다.

**읽기 규약(첫 조회 PR의 결정).**
- 조회 의도는 쓰기처럼 모듈 이름공간 `object` 아래 use case별 `data class`로 둔다(`PaymentQuery.FindAttempts`). 동적 검색 조건이 생기면 그 Query 안에 `Criteria`를 둔다. 지금은 없다.
- 투영 `*View`는 이름공간 밖 최상위 타입이다(`PaymentAttemptView`). 같은 투영을 여러 조회가 돌려줄 수 있어서 쓰기의 `Result`처럼 한 use case에 묶지 않는다.
- 조회 Facade는 쓰기 Facade와 따로 둔다(`PaymentQueryFacade`). `PaymentManagement`는 결제 정본 상태를 바꾸는 유일한 진입점으로 남는다.
- 조회 구현은 `internal/adapter/persistence/query`의 구체 클래스이고 port 인터페이스를 두지 않는다. View는 aggregate 경계에 들어가지 않으므로(domain-model 경계표) domain port에 둘 수 없고, 구현이 하나뿐인 인터페이스는 두지 않는다. aggregate를 거치지 않고 엔티티를 바로 View로 바꾼다. 쿼리는 `JdslQueryExecutor`(Kotlin JDSL)이고 메서드에 `@ReadOnlyTransactional`(`SUPPORTS`)을 선언한다. 한 쿼리라 한 시점이 보장된다.
- Facade는 트랜잭션을 선언하지 않는다. 조회 하나를 위임할 뿐이다. 여러 쿼리를 한 시점에 읽어야 하는 조회가 생기면 그 Facade 메서드가 `Tx.readOnlyTx`로 묶는다.

**결제 시도 조회: `GET /v1/orders/{orderId}/payment-attempts`.**
- `PaymentQueryFacade.findAttempts(PaymentQuery.FindAttempts(orderId, storeId))`가 `List` of `PaymentAttemptView`를 돌려준다. `storeId`는 인증된 단말의 매장이다.
- 그 주문에서 매장이 같은 시도를 모두 `id` 오름차순(UUID v7, 곧 생성 순)으로 읽는다. 회차와 종류를 가리지 않는다. 재결제 뒤에는 거절된 시도와 새 시도가 함께 나온다. 기존 인덱스 `ix_payment_attempt_order (order_id, id)`를 탄다.
- 응답 항목은 `paymentId`, `checkoutNo`, `kind`, `status`, `amount`(통화 기준 decimal string), `currency`다. 단말은 checkout 응답의 `checkoutNo`에서 마지막 시도의 `status`를 보고 `DECLINED`이면 그 `paymentId`로 재결제를 요청한다. `UNKNOWN`이면 기다린다(주기 작업이 확정한다).
- **주문이 없어도, 다른 매장의 주문이어도, 시도가 아직 생기지 않았어도 `200`과 빈 목록이다.** `payment`는 `order`의 테이블을 읽지 않아 셋을 구분할 수 없다. 시도는 checkout 커밋 뒤 이벤트로 만들어지므로 checkout 직후의 빈 목록은 정상이다. 404로 답하면 단말이 폴링 중의 404를 "아직"으로 해석해야 한다. 다른 매장 주문의 존재는 빈 목록이라 드러나지 않는다. 대가로 잘못된 `orderId`도 빈 목록이다(제외).

**재결제: `POST /v1/payment-attempts/{paymentId}/retry`.**
- `PaymentCommand.RetryAuthorization`에 `storeId`를 더한다. Controller는 경로의 `paymentId`와 인증된 단말의 `storeId`로 커맨드를 만든다.
- `PaymentManagement.retry`는 대상 행을 `id`와 `store_id`로 함께 잠근다(`PaymentAttemptRepository.findByIdAndStoreIdForUpdate`). checkout의 매장 조건 잠금과 같다. 매장이 다른 행은 잠기지도 반환되지도 않아 없는 시도와 같은 `404 PAYMENT_ATTEMPT_NOT_FOUND`다. 그 행이 다른 트랜잭션에 잠겨 있어도 기다리지 않는다. 운영자가 구분할 수 있게 null일 때만 잠그지 않는 `findById`로 존재를 확인해 매장 불일치를 WARN(`paymentId`, 요청 `storeId`)으로 남긴다.
- 그 밖의 판정은 기존 `retryAuthorization` 그대로다. 최신 `DECLINED`이면 새 `REQUESTED` 시도, 이미 재결제했으면 최신 시도를 돌려준다. 그래서 첫 요청과 응답을 잃은 재요청이 같은 `200`과 같은 `paymentId`를 받는다. `REQUESTED`·`UNKNOWN`·`APPROVED`·취소 시도는 `422 PAYMENT_NOT_RETRYABLE`, 잠금 대기 3초 초과는 `409 CONCURRENT_REQUEST`다(기존 advice 매핑).
- 응답은 `paymentId`, `status`다. 새 시도의 결말은 조회 API로 본다.

계약: 공개 타입 `PaymentQuery`, `PaymentAttemptView`, `PaymentQueryFacade`가 생긴다. `PaymentCommand.RetryAuthorization`에 `storeId`가 더해진다. 새 오류 code는 없다. 기존 `PAYMENT_ATTEMPT_NOT_FOUND`·`PAYMENT_NOT_RETRYABLE`이 처음 HTTP 응답으로 나간다. 스키마·이벤트·`event_publication`은 바뀌지 않는다.

불변식: 단말은 자기 매장의 결제 시도만 보고 다시 시도한다. 재결제 정합성(겹친 요청이 시도 하나로 수렴, `UNKNOWN` 재결제 금지)은 기존 행 잠금과 aggregate가 그대로 지킨다.

복구: 조회는 상태를 바꾸지 않아 다시 부르면 된다. 재결제 실패(404·422·409)는 아무것도 바꾸지 않으므로 같은 요청을 다시 보내면 된다.

관측성: 재결제는 기존 로그(새 시도 생성 시 `declinedPaymentId`·`paymentId`)를 그대로 쓰고, 매장 불일치 WARN이 더해진다. 조회는 로그를 남기지 않는다.

## 제외
- `GET /v1/terminals/{id}/sync-status`. 빈 배치 `POST /v1/terminals/{terminalId}/sync`가 이미 `lastAckedSequence`를 돌려주고, 항목 결과는 재전송 응답으로 알 수 있다. GET으로 분리할 요구가 생기면 그때 연다.
- 주문 조회 API와 `order`의 조회 Facade. 단말은 checkout 응답으로 주문 상태와 회차를 안다. 결제 조회가 주문 존재를 확인하려면 `payment`에서 `order`로 가는 모듈 의존이 처음 생긴다.
- 없는 주문을 404로 구분하는 것. 위 범위의 근거.
- 운영자가 부르는 재결제 입구. 운영자 인증(#86) 뒤에 연다. 그때 매장 조건 없는 변형을 정한다.
- 시연 단말 화면(`static/terminal`)이 이 API를 부르는 것. 화면 작업이 따로 한다.
- 페이지네이션. 한 주문의 시도는 재결제 횟수만큼만 늘고 상한 근거가 없다. 늘어나는 근거가 생기면 그때 더한다.
- 거절 사유 노출. 저장하지 않는다.
- "최신 회차만 재결제할 수 있다"는 규칙. 재결제는 대상이 그 회차의 최신 승인 시도인지만 보고 주문이 다음 회차로 넘어갔는지는 보지 않는다. 지금은 주문이 `OPEN`으로 돌아가는 전이가 없어 회차가 하나뿐이다. 여러 회차를 여는 PR이 정한다.
- 매장 불일치 WARN의 `terminalId`. 커맨드에 없고 advice 로그의 URI로 추적된다. 단말 단위 추적이 필요해지면 더한다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§4.2·§4.3과 [README Engineering Principles](../../README.md#engineering-principles)의 CQRS 규약이다. 앞 PR은 [payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md), [payment-unknown-resolution](2026-10-01-payment-unknown-resolution.md), [terminal-authentication](2026-10-05-terminal-authentication.md), [tx-convention-kotlin-jdsl](2026-09-28-tx-convention-kotlin-jdsl.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/payment/**
- README.md
- docs/wiki/domain-model.md
- docs/spec/2026-10-06-payment-status-query.md

## 인수 조건
1. `GET /v1/orders/{orderId}/payment-attempts`는 인증된 단말 매장의 그 주문 시도를 `id` 오름차순으로 `paymentId`·`checkoutNo`·`kind`·`status`·`amount`(decimal string)·`currency`와 함께 `200`으로 돌려준다. 재결제한 주문은 거절된 시도와 새 시도가 모두 나온다.
2. 다른 매장의 주문, 없는 주문, 시도가 아직 없는 주문은 모두 `200`과 빈 목록이다.
3. 조회는 `PaymentQueryFacade`가 `persistence/query`의 조회 클래스로 하고, 그 메서드는 `@ReadOnlyTransactional`이며 Kotlin JDSL로 쓴다. 조회 경로에 aggregate가 없다.
4. `POST /v1/payment-attempts/{paymentId}/retry`는 같은 매장의 최신 `DECLINED` 시도면 새 `REQUESTED` 시도의 `paymentId`·`status`를 `200`으로 돌려준다. 같은 요청을 다시 보내면 같은 `paymentId`를 받고 시도는 늘지 않는다.
5. 다른 매장의 시도로 재결제하면 `404 PAYMENT_ATTEMPT_NOT_FOUND`이고 새 시도가 없다. 그 행이 다른 트랜잭션에 잠겨 있어도 기다리지 않는다.
6. 재결제할 수 없는 시도(`UNKNOWN` 등)는 `422 PAYMENT_NOT_RETRYABLE`이고 새 시도가 없다.
7. 두 endpoint 모두 토큰이 없으면 `401 UNAUTHENTICATED`, 경로 식별자가 UUID가 아니면 `400 BAD_REQUEST`다.
8. 두 endpoint의 성공·주요 실패 응답이 REST Docs 스니펫으로 생성된다.
9. 거절 금액(끝자리 1)으로 checkout한 주문은 HTTP 조회에서 `DECLINED` 시도를 보이고, 그 `paymentId`로 HTTP 재결제하면 같은 회차의 시도가 둘이 된다(Kafka 포함 통합).
10. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| 조회 (1~3) | `./gradlew test --tests 'com.resilientpos.payment.*Query*'` | 없음 |
| 재결제 소유권 (4~6) | `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest' --tests 'com.resilientpos.payment.internal.application.PaymentRetry*'` | 없음 |
| Controller (7·8) | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.web.*'` | 조회, 재결제 소유권 |
| HTTP 통합 (9) | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.web.*IntegrationTest'` | Controller |
| 전체 (10) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main` | 모두 |

## 검증
- 정상: 같은 매장 조회는 시도 목록, 거절 시도의 재결제는 새 시도, 재요청은 같은 시도.
- 실패: 다른 매장 조회는 빈 목록, 다른 매장 재결제는 404이고 잠금을 기다리지 않음, `UNKNOWN` 재결제는 422, 토큰 없음 401, 형식 오류 400.
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).
- 동작을 지워 테스트가 빨개지는지 본다: 조회의 매장 조건, 재결제 잠금의 매장 조건.

## Wiki 영향
- [README](../../README.md#engineering-principles): CQRS 규약의 읽기 줄을 확정한 규약으로 고친다("첫 조회 PR에서 정한다"를 지운다).
- [도메인 모델](../wiki/domain-model.md): 용어표의 Facade·명령/조회/결과/뷰 예시를 `PaymentQueryFacade.findAttempts(PaymentQuery.FindAttempts)`와 `PaymentAttemptView`로 고치고, §4.2에 조회 구현의 위치(`persistence/query`, port 없음, aggregate를 거치지 않음)를 적는다. `payment` 행의 공개 Facade에 `PaymentQueryFacade`를 더한다.
- [현재 상태](../wiki/current-state.md)는 볼트에서 갱신한다(두 endpoint, 단말이 거절을 아는 길, 재결제 입구).
- 볼트 rules `domain-modeling-kotlin.md`(§5 읽기 projection, §6 이름공간과 예시)와 `kotlin-idioms-and-design-patterns.md`(§1 팩토리 대상과 예시)가 조회 안에 중첩한 `View`를 가르쳤다. 이번 규약(최상위 View, port 없는 조회 클래스, 엔티티에서 View로의 변환은 조회 파일의 private 확장 함수)으로 볼트 원본을 고치고 `make bootstrap`으로 다시 복사했다. 저장소에 추적되지 않는 경로라 커밋은 없다.
- Kafka·Modulith 이벤트 작업이 아니라 [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)는 바꾸지 않는다.
- 요구사항 변화: 단말이 결제 시도와 거절을 조회하고 재결제를 요청할 수 있다. 정합성 요구는 그대로다.

## 분할 계획
개발 컨텍스트는 하나다. 단말이 결제 결과를 알고 거절된 결제를 다시 시도하는 일이고, 첫 조회라 읽기 규약 확정이 딸려 온다. sync-status와 주문 조회는 목적이 달라 뺐다. 작업 시작 시점(origin/main 581e6f2)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 같은 매장의 시도만 생성 순으로 조회한다 (인수 1~3) `PaymentQueryIntegrationTest`(회차·종류를 가리지 않고 생성 순으로 각 필드와 함께 / 다른 매장 storeId는 빈 목록 / 시도 없는 주문은 빈 목록 / 다른 주문의 시도는 섞이지 않음) | 조회가 빈 목록을 돌려주는 스텁에서 `./gradlew test --tests 'com.resilientpos.payment.*Query*'` — 4개 중 2개(첫째·넷째) assertion 실패, 기대 목록이 비어 나옴. 빈 목록이 기대값인 둘은 스텁에서도 통과했고, 매장 조건 제거로 따로 확인했다 | 같은 명령, 4개 통과 | `./gradlew test --tests 'com.resilientpos.payment.*'`과 구조 테스트 4종(Module·EntityVisibility·TransactionDeclaration·DependencyDirection) 119개 통과. 쿼리에서 매장 조건을 지우면 다른 매장 테스트 1개만 실패 |
| 재결제는 매장 조건으로 잠그고 다른 매장 시도는 없는 시도다 (인수 4~6) `PaymentManagementTest`의 `다른 매장의 재결제는 없는 시도와 같아 거절하고 새 시도도 이벤트도 만들지 않는다`, `PaymentRetryIntegrationTest`의 `다른 매장의 재결제는 시도 행이 잠겨 있어도 기다리지 않고 PAYMENT_ATTEMPT_NOT_FOUND를 받는다`(2초 한도, 잠금 대기 세션 0). 같은 매장의 새 시도와 `UNKNOWN`의 `PAYMENT_NOT_RETRYABLE`은 기존 테스트가 시도의 storeId로 그대로 덮는다 | 커맨드·port에 storeId만 더하고 `retry`가 기존 `findByIdForUpdate`로 잠그는 상태에서 `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest' --tests 'com.resilientpos.payment.internal.application.PaymentRetry*'` — 24개 중 2개 실패. 단위는 예외 없이 새 시도 Result가 나왔고, 통합은 매장을 무시하고 잠긴 행을 기다려 `TimeoutException` | 같은 명령, 24개 통과 | `./gradlew test --tests 'com.resilientpos.payment.*'`과 구조 테스트 4종 121개 통과. 잠금을 `findByIdForUpdate`로 되돌리면 같은 2개가 실패하고, 원복 뒤 24개 통과 |
| 두 endpoint의 HTTP 매핑·인증·오류 변환과 REST Docs (인수 7·8) `PaymentAttemptControllerTest` 8개(조회 200과 항목 필드·인증 매장이 Query에 실림 / 빈 배열 / 재결제 커맨드에 경로 paymentId와 인증 매장 / 404·422·409 / 두 endpoint의 401 / 두 endpoint의 400). REST Docs `payment-attempts-list`·`-list-empty`·`-list-bad-request`·`payment-attempts-retry`·`-retry-not-found`·`-retry-not-retryable`·`-retry-concurrent-request`·`-retry-bad-request` | 매핑만 두고 조회는 빈 목록, 재결제는 입력 paymentId에 `UNKNOWN`을 돌려주는 스텁에서 `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.web.*'` — 8개 중 5개 assertion 실패(조회 항목 필드, 재결제 본문, 404, 422, 409). 빈 배열·401·400은 스텁에서도 통과했다(Facade 앞에서 결정되는 동작) | 같은 명령, 8개 통과 | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.platform.*'`과 구조 테스트 4종 200개 통과. 스니펫 8개 디렉터리 생성 확인 |
| 리뷰 반영, 취소 시도로 재결제하면 같은 회차에 승인 시도가 있어도 422다 (인수 6) `PaymentManagementTest`의 `취소 시도로 재결제하면 같은 회차에 승인 시도가 있어도 거절하고 아무것도 만들지 않는다` | `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest'` — 21개 중 신규 1개 실패. 예외 없이 그 회차의 최신 승인 시도가 Result로 나왔다. "이미 재결제했다" 판정이 대상의 종류를 보지 않아 aggregate의 거절에 닿지 못했다(이전 PR부터 있던 경로를 이번 PR이 HTTP로 열었다) | 판정을 승인 시도에만 적용한 뒤 같은 명령 21개 통과 | `./gradlew test --tests 'com.resilientpos.payment.*'` 121개 통과 |
| 거절 주문을 조회하고 HTTP로 재결제한다 (인수 9, HTTP 위의 2·5) `PaymentAttemptApiIntegrationTest`의 `거절된 주문은 조회에서 DECLINED 시도를 보이고 재결제하면 같은 회차에 시도가 둘이 되며 재요청은 같은 paymentId다`, `다른 매장의 단말은 조회가 200 빈 배열이고 재결제는 404 PAYMENT_ATTEMPT_NOT_FOUND이며 시도가 늘지 않는다` | 해당 없음. 앞 동작들로 이미 구현된 경로를 끝단에서 고정하는 characterization이다. 대신 동작을 지워 확인했다: Controller 조회에 인증 매장 대신 임의 StoreId를 넘기면 두 테스트가 `DECLINED` 대기 20초 `ConditionTimeoutException`으로 실패, 조회의 매장 조건을 지우면 다른 매장 테스트가 빈 배열 단언(`expected: 0`)에서 실패 | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.web.*IntegrationTest'`, 2개 통과(원복 뒤 재실행 포함) | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*'` 243개 통과 |

## 결과
`./gradlew test` 전체 476개 통과, 실패 0(HEAD 013165d, origin/main 581e6f2 기준). `python3 scripts/check_pr.py --base origin/main --worktree` PASS. 제출 전 `python3 scripts/check_pr.py --base origin/main`(`--worktree` 없음) PASS. 제출 직전 origin/main은 그대로 581e6f2이고 열린 PR이 없어 변경 경로 겹침이 없다.

- 조회는 `PaymentQueryFacade` → `PaymentAttemptQueries`(port 없음, Kotlin JDSL, `@ReadOnlyTransactional`) 한 쿼리다. 엔티티에서 View로의 변환은 조회 파일의 private 확장 함수다. 공개 View가 `internal` 엔티티를 알면 안 되어 View에 `from(entity)`를 두지 않았다.
- 재결제는 `(id, store_id)` 잠금으로 다른 매장의 시도를 없는 시도로 만든다. 다른 트랜잭션이 그 행을 잠가도 다른 매장 요청은 2초 안에 404로 끝나는 것을 통합 테스트가 고정한다.
- multi-review(Claude 85점, Codex 실행, Gemini는 CLI가 없어 미실행)를 반영했다. 두 리뷰어가 같이 지적한 것은 셋이다. ① 취소 시도로 재결제하면 그 회차의 최신 승인 시도가 200으로 나갔다. "이미 재결제했다" 판정을 승인 시도에만 적용해 aggregate의 거절(422)에 닿게 했다. ② 이 결과 절이 비어 있었다. ③ domain-model 공개 API 행에 조회 이름공간과 View가 빠져 있었다. Claude만 지적한 것 중 볼트 rules의 옛 View 규약(Wiki 영향 절), 대기 실패 메시지가 대기 전 상태를 찍던 문제, 이전 회차 재결제와 WARN의 `terminalId`(제외 절)를 반영했다. `PaymentRetryIntegrationTest`의 `pg_stat_activity` 단언은 #81 checkout 선례와 같은 모양이라 그대로 두었다. 401 응답에 REST Docs를 두지 않은 것도 checkout 선례와 같다.
- 조회 경로의 테스트는 빈 목록 스텁에서 빈 목록이 기대값인 경우가 통과해서, 매장 조건을 지우는 동작 제거로 따로 확인했다.
