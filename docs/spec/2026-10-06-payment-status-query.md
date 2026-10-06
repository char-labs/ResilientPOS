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
- Kafka·Modulith 이벤트 작업이 아니라 [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)는 바꾸지 않는다.
- 요구사항 변화: 단말이 결제 시도와 거절을 조회하고 재결제를 요청할 수 있다. 정합성 요구는 그대로다.

## 분할 계획
개발 컨텍스트는 하나다. 단말이 결제 결과를 알고 거절된 결제를 다시 시도하는 일이고, 첫 조회라 읽기 규약 확정이 딸려 온다. sync-status와 주문 조회는 목적이 달라 뺐다. 작업 시작 시점(origin/main 581e6f2)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 같은 매장의 시도만 생성 순으로 조회한다 (인수 1~3) | 미실행 | 미실행 | 미실행 |
| 재결제는 매장 조건으로 잠그고 다른 매장 시도는 없는 시도다 (인수 4~6) | 미실행 | 미실행 | 미실행 |
| 두 endpoint의 HTTP 매핑·인증·오류 변환과 REST Docs (인수 7·8) | 미실행 | 미실행 | 미실행 |
| 거절 주문을 조회하고 HTTP로 재결제한다 (인수 9) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
