# PR spec: 결제·주문의 트랜잭션 전파와 이벤트 발행 선언을 정리한다

## 목적
트랜잭션 생명주기를 선언만 보고 읽기 어렵고, IDE가 이벤트 리스너의 발행 지점을 찾지 못한다.

- 전파 설정이 네 종류(`@Transactional` 기본값, `NOT_SUPPORTED`, `NEVER`, 트랜잭션 확인 `check`)로 흩어져 있다. `NEVER`와 `check`는 호출자의 트랜잭션 상태를 보고 거절하는 방식이라, 메서드가 어떤 트랜잭션에서 도는지를 호출자까지 따라가야 안다.
- `RedisCheckoutLock`은 메서드 안에서 `TransactionSynchronizationManager.isSynchronizationActive()`와 `UUID.randomUUID()`를 정적으로 부른다. 토큰은 저장소의 다른 식별자와 달리 주입된 `IdentifierGenerator`를 거치지 않는다.
- 발행 지점 6곳이 모두 `transition.events.forEach(eventPublisher::publishEvent)`다. IntelliJ Spring 플러그인은 `publishEvent` 호출의 첫 인자 정적 타입이 리스너 파라미터 타입에 대입될 때만 발행 지점으로 잇는데, 메서드 참조라 인자가 `forEach`의 함수 타입(`KFunction`)으로 읽히고 요소 타입도 `DomainEvent`다. 그래서 리스너 4개 모두 "이벤트 퍼블리셔를 찾을 수 없습니다"가 뜬다.
- `authorize`가 provider를 부르는 동안 DB 자원을 쥐지 않는 것은, 시도를 읽는 `findById`에 Spring Data가 붙인 읽기 전용 트랜잭션이 끝나며 EntityManager를 놓기 때문이다. 이 의존이 코드 어디에도 적혀 있지 않다.

기대 결과: 명시적 전파 설정은 `NOT_SUPPORTED`와 `REQUIRES_NEW`로 한정하고, 지금 쓰는 것은 `NOT_SUPPORTED`뿐이다. 트랜잭션 밖이어야 하는 메서드는 호출자 트랜잭션을 거절하지 않고 멈춘다. 이벤트는 모듈마다 둔 publisher가 구체 타입으로 발행해 IDE가 리스너와 발행 지점을 잇는다. 업무 동작(결제 시도 생성·승인·재결제, 주문 전이, 잠금)은 바뀌지 않는다.

## 범위
소유 모듈은 `payment`와 `order`다.

**전파 설정을 `NOT_SUPPORTED`로 모은다.**
- `PaymentManagement.authorize`와 `retryAuthorization`의 `@Transactional(propagation = Propagation.NEVER)`를 `NOT_SUPPORTED`로 바꾼다. 트랜잭션 안에서 불리면 거절하던 것을, 호출자 트랜잭션을 멈추고 트랜잭션 없이 도는 것으로 바꾼다. provider 호출은 어느 경우에도 트랜잭션 밖이고(§4.6), 적용 트랜잭션은 지금처럼 `TransactionOperations`가 새로 열어 반환 전에 커밋한다. 호출자 트랜잭션이 나중에 롤백돼도 적용 결과는 남는다.
- 한계: 멈춘 호출자 트랜잭션이 커밋하지 않은 행은 보이지 않고, 호출자가 그 시도 행을 잠그고 있으면 적용 트랜잭션이 행 잠금 대기 한도(3초)까지 기다린 뒤 실패한다. 지금 호출자는 트랜잭션이 없는 리스너뿐이라 해당하지 않는다. 호출자 트랜잭션에 합류하지 않으므로 §4.6 sync intake처럼 한 커밋으로 묶어야 하는 흐름 안에서 부르지 않는다. 그 안에서 부르면 바깥이 롤백돼도 승인 적용과 재결제 시도가 따로 커밋된다. 두 메서드 문서와 wiki에 적는다.
- 리스너 4개의 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`는 그대로 둔다. 기본값 `REQUIRES_NEW`를 쓰면 Facade가 리스너 트랜잭션에 합류해 provider 호출이 트랜잭션 안에 들어간다.
- Facade의 `@Transactional`(기본값 `REQUIRED`)은 그대로 둔다. §4.6 sync intake가 바깥 트랜잭션에 합류하는 계약이다.

**`RedisCheckoutLock`의 정적 호출을 줄인다.**
- `check(TransactionSynchronizationManager.isSynchronizationActive())`를 지운다. 어노테이션은 붙이지 않는다. 트랜잭션 밖에서 부르면 해제를 거는 `registerSynchronization`이 같은 조건(동기화 비활성)에서 `IllegalStateException`을 던진다. 잠금을 얻은 뒤라 잘못 부른 경우 그 키는 TTL 10초까지 남는다. 프로그래밍 오류에서만 생기고 테스트에서 드러나므로 감수한다.
- 잠금 토큰 `UUID.randomUUID()`를 생성자로 주입한 `foundation.IdentifierGenerator`의 `next()`로 바꾼다. 토큰은 요청마다 달라야 할 뿐이라 UUID v7이어도 해제 판정(토큰 일치)이 같다.
- `registerSynchronization`은 그대로 둔다(검토한 대안 참고).

**이벤트 발행을 모듈별 publisher로 옮긴다.**
- `order.internal.application.OrderEventPublisher`와 `payment.internal.application.PaymentEventPublisher`를 둔다. 둘 다 `ApplicationEventPublisher`를 생성자로 주입받는다.
- 이벤트 타입마다 구체 타입 파라미터를 받아 `publishEvent`에 넘기는 `publish` 메서드를 따로 둔다. 타입별 분기(`when`)는 두지 않는다. IDE가 이 호출의 인자를 구체 타입으로 읽는다(`OrderPaidListenerIntegrationTest`의 같은 형태로 확인).
- `order`: `OrderEvent.Created`, `OrderPaymentRequested`, `OrderPaid`. `payment`: `PaymentEvent.AttemptCreated`, `PaymentEvent.Declined`, `PaymentEvent.Unknown`, `PaymentApproved`.
- `OrderCommandFacade`와 `PaymentManagement`는 `ApplicationEventPublisher` 대신 자기 모듈 publisher를 주입받아 `transition.events.forEach(eventPublisher::publish)`로 발행한다. 어느 `publish`가 불릴지는 전이의 이벤트 타입으로 컴파일러가 정한다. 발행은 지금처럼 Facade 트랜잭션 안이다.

**전이마다 이벤트 타입을 하나로 좁힌다.**
- `Order.create`는 `OrderEvent.Created`, `requestCheckout`은 `OrderPaymentRequested`, `markPaid`는 `OrderPaid`, `PaymentAttempt.retry`는 `PaymentEvent.AttemptCreated`로 `Transition`의 이벤트 타입을 좁힌다.
- `PaymentAttempt.applyAuthorization(outcome)`를 `approve(providerReference)`·`decline(providerReference)`·`markUnknown()`으로 나눈다. provider 승인에는 참조가 늘 있으므로 공개 `approve`는 null이 아닌 참조만 받고, 참조 없는 승인은 `approveIfFree`만 낸다. 셋 다 `REQUESTED`가 아니면 지금과 같은 `PAYMENT_RESULT_ALREADY_APPLIED`로 거절하고, 상태와 사실은 각 메서드 안에서 함께 정한다. provider 결과(`AuthorizationOutcome`)를 어느 전이로 옮길지는 `PaymentManagement`가 `when (outcome)`으로 고른다. sealed 타입이라 `else`가 없다. 세 분기는 같은 private 함수로 저장한 뒤 발행한다(Facade 순서: 저장 → 발행). 발행 메서드는 그 함수에 넘긴 메서드 참조(`eventPublisher::publish`)의 기대 타입으로 정해진다.
- `PaymentAttempt.create`는 금액과 무관하게 `REQUESTED` 시도와 `AttemptCreated`만 낸다. 0원 승인은 새 도메인 메서드 `approveIfFree()`가 맡는다. 0원이면 provider 참조 없는 `approve` 결과를, 아니면 `null`을 돌려준다. 0원이면 provider를 부르지 않고 승인한다는 판단은 도메인에 남는다. `createAttempt`는 두 전이를 이어 최종 시도(0원이면 `APPROVED`)를 저장하고 생성·승인 사실을 그 순서로 발행한다. 저장되는 행과 이벤트 순서·내용은 지금과 같다.

**트랜잭션 블록 결과를 non-null로 받는 확장 함수를 둔다.**
- `platform`에 확장 함수 `TransactionOperations.inTransaction(action)`을 둔다. 블록의 결과 타입은 null이 될 수 없고, 반환값도 null이 아니다. `execute`가 nullable을 돌려줘 호출부마다 `requireNotNull(transactions.execute { ... })`를 쓰던 것을 `transactions.inTransaction { ... }`으로 줄인다. 블록은 `TransactionStatus`를 받지 않는다.
- spring-tx 7.0.9에는 `TransactionOperations`용 Kotlin 확장이 없다(반응형 `TransactionalOperatorExtensions`만 있다). Spring 타입이라 `foundation`·`internal.domain`이 아닌 `platform`에 둔다(`DependencyDirectionTest`).
- 쓰는 곳: `PaymentManagement.authorize`·`retryAuthorization`, `PaymentAttemptCreationLockIntegrationTest`.

**반복되는 호출을 확장 함수·멤버·공용 헬퍼로 줄인다.**
프로젝트 전체를 훑어 두 곳 이상 반복되는 호출만 고른다. 우리 값 객체는 멤버로, 외부 라이브러리 타입은 확장 함수로, 수신 객체가 필요 없는 것은 최상위 함수로 둔다. 이미 설치된 라이브러리 확장이 있으면 그것을 쓴다.
- 테스트 대기 루프 `awaitUntil` 9벌을 테스트 공용 최상위 함수 하나로 모은다. 발행 기록을 덧붙이던 변형은 실패 진단 인자로 흡수한다. 같은 루프를 인라인으로 쓴 곳도 옮긴다.
- 테스트의 `JdbcTemplate` 조회를 spring-jdbc에 있는 reified 확장(`queryForObject`·`queryForList`의 타입 인자 형태)으로 바꾸고, 여러 파일에 반복되는 주문 상태·checkout 행 수 조회는 테스트 확장 함수로 둔다.
- `OrderPersistenceAdapter`의 `findById(id).orElse(null)`을 spring-data의 `findByIdOrNull`로 바꾼다.
- `Money`에 `isZero`·`isNegative` 멤버를 두고 0과 비교하던 도메인 코드 3곳을 바꾼다. 값 객체라 확장이 아니라 멤버다.
- 주문 생성 뒤 checkout을 요청하는 테스트 헬퍼 4벌과 인라인 반복을 테스트 확장 함수로, `Order.restore`로 필드 하나만 바꾼 사본을 만드는 테스트 코드 4곳을 테스트 확장 함수로 모은다.
- 직접 만든 `DomainException` 단언(`assertFailsWithDomain`, `runCatching` 뒤 `assertIs`)을 kotlin.test의 `assertFailsWith`로 바꾼다.
- 테스트의 `ApplicationEvents` 타입별 조회와 `MeterRegistry` 발행 기록 metric 조회를 테스트 확장 함수로 둔다.
- 제외: 도메인 `if`·`throw`를 한 줄 함수로 바꾸는 것(스타일 변경), executor 정리를 `use`로 바꾸는 것(대기 상한이 사라짐), Hibernate 문장 수 측정·커밋 뒤 발행 헬퍼(이득이 작음).

**남은 리뷰 지적을 반영한다.**
- 이미 결말이 난 시도에 `approveIfFree`를 부르면 거절하는지 테스트한다. `RedisCheckoutLock`이 잠금을 얻으면 해제 동기화를 등록하는지 단위 테스트한다.
- `authorize` 문서에 0원 시도는 만들자마자 승인되어 `REQUESTED`로 오지 않는다는 전제를 적는다.

**트랜잭션 선언 규칙을 테스트로 고정한다.**
- 클래스에 `@Transactional`을 붙이지 않는다. 클래스에 붙이면 이후에 더한 조회 메서드까지 트랜잭션이 걸린다. `TransactionDeclarationTest`(ArchUnit)가 main 코드의 클래스 선언을 검사한다. 지금 위반은 없다.

**숨은 읽기 전용 트랜잭션에 기대는 곳을 적는다.**
- `authorize` 문서에 `findById`의 읽기 전용 트랜잭션이 EntityManager를 놓는다는 사실과, 파생 쿼리로 바꾸면 provider 호출 내내 EntityManager가 묶인다는 사실을 적는다. `NOT_SUPPORTED` 구간에서 `findById`와 파생 쿼리를 각각 부르고 EntityManager 바인딩을 본 임시 테스트로 확인했다(각각 풀림, 묶임).

계약: 이벤트 타입과 필드, `listener_id`, 오류 code, `CheckoutLock` port, Redis 키·TTL·대기 한도는 바뀌지 않는다. 트랜잭션 안에서 부른 `authorize`·`retryAuthorization`은 `IllegalTransactionStateException` 대신 호출자 트랜잭션과 따로 실행된다.

복구·관측성: 바뀌지 않는다.

## 제외
- `acquire` 대기 루프의 `System.nanoTime()`·`Thread.sleep`.
- `fulfillment` 모듈. 이벤트를 발행하지 않는다.
- 리스너 선언(`@ApplicationModuleListener`)을 다른 애너테이션으로 바꾸는 것.
- `FulfillmentCommandFacade`의 `@Transactional`. 이벤트를 발행하지 않는다.
- `OrderPersistenceAdapter.save`의 `@Transactional`. Facade 트랜잭션에 합류해 자식 행 저장을 묶는 선언이다.
- 트랜잭션 밖 발행을 컴파일 단계에서 막는 장치. 발행이 트랜잭션 안이라는 전제는 지금처럼 Facade의 `@Transactional`과 wiki 규칙이 지킨다.

## 검토한 대안
- **`@Transactional(propagation = Propagation.MANDATORY)`로 잠금의 트랜잭션 전제를 선언**: `registerSynchronization`이 이미 하는 확인을 한 번 더 하고 전파 설정 종류를 늘린다.
- **`registerSynchronization`을 감싸는 타입을 새로 선언해 주입**: 사용자가 래퍼 타입을 새로 두지 말라고 결정했다. 호출이 한 곳뿐이다.
- **`@TransactionalEventListener`로 잠금 해제**: 잠금마다 이벤트를 발행하면 Spring Modulith가 `EVENT_PUBLICATION`에 잠금마다 행을 남긴다.
- **`registerSynchronization`을 획득 전에 불러 잘못된 호출에서 키를 남기지 않기**: 잠그지 못한 경우에도 해제가 불려 획득 여부를 담을 가변 변수나 헛된 해제 호출이 생긴다.
- **`authorize`에 `NEVER`를 유지**: 트랜잭션 안의 호출을 막아 주지만 전파 설정이 한 종류 늘고, 메서드가 트랜잭션 밖에서 돈다는 사실을 호출자 쪽 조건으로 적는다. `NOT_SUPPORTED`는 같은 사실을 메서드 쪽에서 보장한다.
- **적용 트랜잭션을 `REQUIRES_NEW`로 열기**: 적용이 호출자 트랜잭션과 갈리지만 provider 호출은 호출자 트랜잭션 안에 남아 §4.6을 지키지 못한다.
- **publisher가 이벤트 목록을 받아 `when`으로 타입별 메서드에 나눠 주기**: 도메인을 건드리지 않지만 모든 분기가 같은 발행이라 같은 말을 되풀이하고, 모르는 타입을 위한 `else`가 필요하다. 사용자 결정으로 기각했다.
- **결제 전이는 그대로 두고 주문 전이와 `retry`만 좁히기**: 도메인 변경이 작지만 `PaymentApproved`를 구체 타입으로 발행하는 곳이 없어 `PaymentApprovedListener`가 계속 발행 지점을 못 찾는다.
- **`applyAuthorization`이 결과별 sealed 타입을 돌려주기**: 도메인 메서드는 하나로 남지만 결과 타입이 새로 생기고, 호출부가 결국 그 타입으로 다시 분기한다.

- **트랜잭션이 필요 없는 단건 조회·단건 쓰기에서 `@Transactional`을 빼기**: 트랜잭션을 여는 쿼리 비용을 줄이지만, 이 저장소의 쓰기는 발행 기록을 같은 커밋에 넣어야 해서 한 행이어도 트랜잭션이 필요하다. 트랜잭션을 거는 조회 서비스는 아직 없다.
- **읽기 전용 조회에 `readOnly = true`와 `SUPPORTS`를 함께 선언**: 단독 호출에서 트랜잭션을 만들지 않지만 지금 그런 조회가 없고, 전파 설정 종류를 늘린다.
- **Spring Data `findById`를 트랜잭션 없는 쿼리로 덮어 숨은 트랜잭션을 없애기**: 쿼리는 줄지만 `authorize`에서는 그 트랜잭션이 EntityManager를 놓아 주는 장치라 없애면 provider 호출 내내 DB 자원을 쥔다.
- **publisher가 `TransactionStatus`를 트랜잭션 증거로 받기**: 트랜잭션 밖 발행이 컴파일되지 않지만, Spring 트랜잭션 타입이 쓰지 않는 인자로 발행 API에 드러나고 Facade가 `@Transactional` 대신 `TransactionOperations` 블록으로 트랜잭션을 열어야 한다. 사용자 결정으로 기각했다.

- **트랜잭션 실행기를 정적 객체로 두고 `readOnlyTx { }`처럼 어디서나 부르기**: 호출부가 짧아지지만 주입 대신 정적 접근이 생기고, 트랜잭션을 여는 곳이 Facade 밖으로 퍼진다. 주입받은 `TransactionOperations`의 확장으로 둔다.

## 관련 문서
[도메인 모델](../wiki/domain-model.md) §4.3·§4.6, [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md). 잠금은 [payment-advisory-lock](2026-09-26-payment-advisory-lock.md), 승인의 트랜잭션 경계는 [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md)이 정했다.

공식 문서 대조:
- Spring Modulith 이벤트(https://docs.spring.io/spring-modulith/reference/events.html): 발행 기록은 `ApplicationEventPublisher`로 낸 이벤트를 트랜잭션 리스너 단위로 남긴다. 발행을 모듈 publisher로 감싸도 같은 `publishEvent`를 거치므로 기록 방식은 같다. `@ApplicationModuleListener`는 `REQUIRES_NEW` 트랜잭션을 함께 선언한다.
- Spring Framework `Propagation.NOT_SUPPORTED`(https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Propagation.html): 트랜잭션 없이 실행하고 현재 트랜잭션이 있으면 멈춘다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/internal/adapter/lock/RedisCheckoutLock.kt
- src/main/kotlin/com/resilientpos/payment/PaymentManagement.kt
- src/main/kotlin/com/resilientpos/platform/TransactionOperationsExtensions.kt
- src/main/kotlin/com/resilientpos/foundation/Money.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderLine.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/test/kotlin/com/resilientpos/**
- src/main/kotlin/com/resilientpos/payment/internal/application/PaymentEventPublisher.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/payment/internal/domain/PaymentAttempt.kt
- src/main/kotlin/com/resilientpos/payment/internal/adapter/persistence/command/PaymentAttemptEntity.kt
- src/main/kotlin/com/resilientpos/order/internal/application/OrderEventPublisher.kt
- src/test/kotlin/com/resilientpos/payment/internal/adapter/lock/RedisCheckoutLockTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/application/PaymentAttemptCreationLockIntegrationTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/application/PaymentAuthorizationIntegrationTest.kt
- src/test/kotlin/com/resilientpos/payment/PaymentManagementTest.kt
- src/test/kotlin/com/resilientpos/order/OrderCommandFacadeLineLimitTest.kt
- src/test/kotlin/com/resilientpos/TransactionDeclarationTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/application/PaymentRetryIntegrationTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/domain/PaymentAttemptAuthorizationTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/domain/PaymentAttemptTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/adapter/persistence/command/PaymentAttemptPersistenceIntegrationTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-28-checkout-lock-static-calls.md

## 인수 조건
1. 트랜잭션 밖에서 `CheckoutLock` 빈의 `lockUntilTransactionEnds`를 부르면 `IllegalStateException`이 난다.
2. 트랜잭션 안에서 `authorize`를 부르면 provider를 한 번 부르고, 그동안 DB 자원을 쥐지 않으며, 바깥 트랜잭션을 롤백해도 적용 결과(`APPROVED`)가 남는다.
3. main 코드의 명시적 전파 설정은 `NOT_SUPPORTED`뿐이다(허용 목록은 `NOT_SUPPORTED`·`REQUIRES_NEW`). `RedisCheckoutLock.kt`에는 `isSynchronizationActive`·`UUID.randomUUID` 호출과 `@Transactional`이 없다.
4. main 코드에서 `ApplicationEventPublisher.publishEvent`를 부르는 곳은 두 publisher뿐이고, 모든 호출의 인자가 구체 이벤트 타입으로 선언된 파라미터다. publisher에 `when`이 없다. 이 규칙과 인수 조건 3·5-2는 `grep`으로만 확인한다.
4-1. 모든 전이의 `Transition` 이벤트 타입이 구체 이벤트 하나다. `applyAuthorization`은 없고, 0원 시도는 `create`와 `approveIfFree`로 지금과 같은 행(`APPROVED`, 참조 없음)과 이벤트(생성 뒤 승인)를 남긴다.
5. 기존 테스트(이벤트 발행 순서와 내용, 발행 기록, 잠금, 동시성)가 깨지지 않는다.
5-1. 트랜잭션 안에서 `retryAuthorization`을 불러도 바깥을 롤백하면 새 시도가 남는다(따로 커밋). `authorize`가 미확인 결말을 적용하면 `PaymentEvent.Unknown`을 발행한다. 0원 시도는 최종 상태(`APPROVED`)로 한 번만 저장된다.
5-2. main 코드에 `requireNotNull(transactions.execute` 호출이 없다.
6. main 코드의 클래스에 `@Transactional`이 붙으면 `TransactionDeclarationTest`가 실패한다.
7. `authorize` 문서가 `findById`의 읽기 전용 트랜잭션에 기대는 이유를 적는다.

## 검증
- `./gradlew test --tests 'com.resilientpos.payment.*'`와 `--tests 'com.resilientpos.order.*'`로 좁혀 돌린 뒤 `./gradlew test` 전체를 돌린다. Docker가 필요하다.
- 실패 시나리오: `authorize`가 `NEVER`면 인수 조건 2의 테스트가 `IllegalTransactionStateException`으로 실패한다. 해제 등록이 트랜잭션 확인 없이 바뀌면 인수 조건 1의 테스트가 예외 없이 끝나 실패한다.
- 인수 조건 3·4는 `grep`으로 확인한다. 인수 조건 6은 규칙을 뒤집어(클래스에 붙은 경우만 허용) 실패하는지 본 뒤 되돌린다.
- IDE 발행 지점: IntelliJ Spring 플러그인이 쓰는 판정(`publishEvent` 참조를 감싼 호출의 첫 인자 타입)을 inspection 스크립트로 흉내 내어, 지금 발행 지점은 `forEach`와 `KFunction`으로 읽히고 구체 타입 파라미터는 그 타입으로 읽히는 것을 확인했다. 변경 후 머지된 checkout에서 리스너 거터를 한 번 더 본다.
- `python3 scripts/check_pr.py --base 5eece85 --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md): 트랜잭션 밖이어야 하는 메서드는 `NOT_SUPPORTED`로 호출자 트랜잭션을 멈추고 거절하지 않는다는 규칙, 이벤트는 모듈 publisher가 구체 타입으로 발행한다는 규칙을 이벤트 발행 규칙 옆에 적는다.
- [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md): `ApplicationEventPublisher` 항목의 "이 프로젝트에서"에 모듈 publisher를 적고, 8절에 결정을 [합의]로 더한다. 이 문서는 Obsidian 볼트 원본을 가리키는 링크라 저장소 커밋에 들어가지 않는다.
- [도메인 모델](../wiki/domain-model.md): 전파 규칙에 클래스 레벨 `@Transactional` 금지와 Spring Data 기본 메서드의 읽기 전용 트랜잭션을 더한다.
- 요구사항 변화: 없다. 업무 동작과 이벤트 계약이 그대로이고, 트랜잭션 안에서 부른 승인·재결제가 거절 대신 따로 실행되는 것은 지금 호출자가 없는 경로다.

## 분할 계획
확장 함수 정리까지 넣으면 추가와 삭제 합계가 경고선(900줄)을 넘고 실패 한도(1400줄) 안일 것으로 본다. 확장 함수 정리는 별도 목표라 후속 PR로 나누는 것이 기본이지만, 사용자가 이 PR에 한 번에 넣기로 결정했다. 대부분 중복을 지우는 테스트 변경이라 리뷰는 파일별로 기계적으로 볼 수 있다.

## TDD 검증 기록
업무 동작은 바뀌지 않는 리팩터링이다. 바뀌는 두 경계(트랜잭션 밖 잠금 호출, 트랜잭션 안 승인 호출)는 통합 테스트로 RED→GREEN을 본다. publisher의 타입별 분기는 기존 Facade 테스트의 발행 검증이 덮는다. 클래스 선언 규칙은 위반을 넣어 실패를 본다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 트랜잭션 밖에서 잠그면 거절한다 | 기준 코드와 최종 코드에서 모두 통과하는 회귀 방어 테스트다. RED는 되돌린 전파 설정 변형(`MANDATORY`)에서 `IllegalTransactionStateException`으로 관찰했다(`./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentAttemptCreationLockIntegrationTest'`) | 같은 명령, 설정을 지운 뒤 통과 | `./gradlew test` 통과 |
| 트랜잭션 안에서 승인을 요청해도 바깥 트랜잭션과 따로 적용한다 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentAuthorizationIntegrationTest'`, `NEVER`가 `IllegalTransactionStateException`으로 거절해 실패 | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*'`, `NOT_SUPPORTED`로 바꾸고 publisher를 옮긴 뒤 통과 | `./gradlew test` 통과 |
| 결말별 전이(`approve`·`decline`·`markUnknown`)와 `approveIfFree`, 이미 결말이 난 시도에 세 전이 모두 거절 | `./gradlew compileTestKotlin`, 새 메서드가 없어 테스트 컴파일 실패 | `./gradlew test`, 도메인을 나누고 0원 persistence 테스트를 `approveIfFree`로 이은 뒤 통과 | `./gradlew test` 통과 |
| 승인을 적용하면 저장한 뒤 승인 사실을 발행한다 | `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest'`, 발행 시점 갱신 횟수가 0이라 실패(발행이 저장보다 먼저) | 같은 명령, `updateAndPublish`로 저장 뒤 발행해 통과 | `./gradlew test` 통과 |
| 트랜잭션 안에서 재결제를 요청해도 바깥 트랜잭션과 따로 커밋한다 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentRetryIntegrationTest'`, `retryAuthorization`을 잠시 `NEVER`로 되돌리자 `IllegalTransactionStateException`으로 실패 | 같은 명령, `NOT_SUPPORTED`로 원복해 통과 | `./gradlew test` 통과 |
| 클래스에 트랜잭션을 선언하지 않는다 | `./gradlew test --tests 'com.resilientpos.TransactionDeclarationTest'`, `FulfillmentCommandFacade` 클래스에 `@Transactional`을 임시로 붙여 규칙 위반으로 실패 | 같은 명령, 되돌린 뒤 통과 | `./gradlew test` 통과 |

## 결과
개발 전 spec을 독립 커밋으로 작성하고, 범위를 넓힐 때마다 구현 전에 spec만 다시 커밋했다. 구현 후 아래를 확인했다.

- `RedisCheckoutLock`에서 `check`를 지우고 어노테이션은 붙이지 않았다. 트랜잭션 밖 호출은 `registerSynchronization`이 `IllegalStateException`으로 거절한다. 토큰은 주입한 `IdentifierGenerator.next()`로 만든다.
- `PaymentManagement.authorize`·`retryAuthorization`을 `NOT_SUPPORTED`로 바꿨다. 트랜잭션 안에서 부른 `authorize`는 provider를 한 번 부르고, 호출 중 DB 자원을 쥐지 않으며, 바깥을 롤백해도 `APPROVED`가 남는다.
- `OrderEventPublisher`·`PaymentEventPublisher`를 더하고 두 Facade가 이를 거쳐 발행한다. 발행 순서·내용과 발행 기록 테스트는 고치지 않고 통과했다. Facade를 직접 만드는 단위 테스트 두 곳만 생성자 인자를 맞췄다.
- 명시적 전파 설정은 `NOT_SUPPORTED` 6곳(리스너 4, `PaymentManagement` 2)뿐이다. main 코드의 `publishEvent` 호출은 두 publisher의 공개 `publish` 메서드 7곳뿐이고 인자는 모두 구체 타입 파라미터다.
- IDE: 플러그인 판정을 흉내 낸 inspection 스크립트로, 이전 발행 지점은 `forEach`·`KFunction`으로 읽히고 구체 타입 파라미터(`OrderPaidListenerIntegrationTest`)는 `com.resilientpos.contract.OrderPaid`로 읽히는 것을 확인했다. IDE가 연 프로젝트가 main checkout이라 이 브랜치의 거터는 머지 뒤 확인한다.
- `NOT_SUPPORTED` 구간에서 `findById`는 EntityManager를 놓고(바인딩 없음) 파생 쿼리는 묶는(바인딩 있음) 것을 임시 통합 테스트로 확인하고 지웠다. 이 사실을 `authorize` 문서와 wiki에 적었다.
- `TransactionDeclarationTest`는 클래스에 붙인 `@Transactional`을 잡는다(임시 위반으로 확인).
- 트랜잭션 증거(`TransactionStatus`)를 발행에 넘기던 변경은 되돌렸다. Facade는 `@Transactional`로 트랜잭션을 연다.
- publisher는 이벤트 타입마다 `publish`를 따로 두고 `when`이 없다. Facade는 `transition.events.forEach(eventPublisher::publish)`로 부르고, 전이마다 `Transition`의 이벤트 타입이 하나다.
- `applyAuthorization`을 `approve`·`decline`·`markUnknown`으로 나눴고 `PaymentManagement`가 `when (outcome)`으로 고른다. 0원 시도는 `create` 뒤 `approveIfFree`로 승인해 지금과 같은 행과 이벤트 순서를 남긴다(`PaymentManagementTest`의 0원 발행 순서 검증이 그대로 통과).
- multi-review(Claude 84점, Codex 76점, Gemini는 CLI가 없어 미실행)를 반영했다. 두 리뷰가 모두 짚은 wiki §4.5의 옛 서술(`Transition`이 `DomainEvent`까지 넓어진다)과 승인 결과를 저장보다 먼저 발행하던 순서를 고쳤다. 그 밖에 미확인 발행·0원 단일 저장·재결제 따로 커밋 테스트, 공개 `approve`의 참조 non-null, `RedisCheckoutLock`·wiki·`current-state` 문구, sync intake 안에서 부르지 않는다는 문서를 반영했다.
- `TransactionOperations.inTransaction` 확장을 `platform`에 두고 `requireNotNull(transactions.execute` 호출을 없앴다.
- `./gradlew test`: 250개 통과, 실패 0.
