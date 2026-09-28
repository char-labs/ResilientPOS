# PR spec: 결제·주문의 트랜잭션 전파와 이벤트 발행 선언을 정리한다

## 목적
트랜잭션 생명주기를 선언만 보고 읽기 어렵고, IDE가 이벤트 리스너의 발행 지점을 찾지 못한다.

- 전파 설정이 네 종류(`@Transactional` 기본값, `NOT_SUPPORTED`, `NEVER`, 트랜잭션 확인 `check`)로 흩어져 있다. `NEVER`와 `check`는 호출자의 트랜잭션 상태를 보고 거절하는 방식이라, 메서드가 어떤 트랜잭션에서 도는지를 호출자까지 따라가야 안다.
- `RedisCheckoutLock`은 메서드 안에서 `TransactionSynchronizationManager.isSynchronizationActive()`와 `UUID.randomUUID()`를 정적으로 부른다. 토큰은 저장소의 다른 식별자와 달리 주입된 `IdentifierGenerator`를 거치지 않는다.
- 발행 지점 6곳이 모두 `transition.events.forEach(eventPublisher::publishEvent)`다. IntelliJ Spring 플러그인은 `publishEvent` 호출의 첫 인자 정적 타입이 리스너 파라미터 타입에 대입될 때만 발행 지점으로 잇는데, 메서드 참조라 인자가 `forEach`의 함수 타입(`KFunction`)으로 읽히고 요소 타입도 `DomainEvent`다. 그래서 리스너 4개 모두 "이벤트 퍼블리셔를 찾을 수 없습니다"가 뜬다.

기대 결과: 명시적 전파 설정은 `NOT_SUPPORTED` 하나만 쓴다. 트랜잭션 밖이어야 하는 메서드는 호출자 트랜잭션을 거절하지 않고 멈춘다. 이벤트는 모듈마다 둔 publisher가 구체 타입으로 발행해 IDE가 리스너와 발행 지점을 잇는다. 업무 동작(결제 시도 생성·승인·재결제, 주문 전이, 잠금)은 바뀌지 않는다.

## 범위
소유 모듈은 `payment`와 `order`다.

**전파 설정을 `NOT_SUPPORTED`로 모은다.**
- `PaymentManagement.authorize`와 `retryAuthorization`의 `@Transactional(propagation = Propagation.NEVER)`를 `NOT_SUPPORTED`로 바꾼다. 트랜잭션 안에서 불리면 거절하던 것을, 호출자 트랜잭션을 멈추고 트랜잭션 없이 도는 것으로 바꾼다. provider 호출은 어느 경우에도 트랜잭션 밖이고(§4.6), 적용 트랜잭션은 지금처럼 `TransactionOperations`가 새로 열어 반환 전에 커밋한다. 호출자 트랜잭션이 나중에 롤백돼도 적용 결과는 남는다.
- 한계: 멈춘 호출자 트랜잭션이 커밋하지 않은 행은 보이지 않고, 호출자가 그 시도 행을 잠그고 있으면 적용 트랜잭션이 행 잠금 대기 한도(3초)까지 기다린 뒤 실패한다. 지금 호출자는 트랜잭션이 없는 리스너뿐이라 해당하지 않는다.
- 리스너 4개의 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`는 그대로 둔다. 기본값 `REQUIRES_NEW`를 쓰면 Facade가 리스너 트랜잭션에 합류해 provider 호출이 트랜잭션 안에 들어간다.
- Facade의 `@Transactional`(기본값 `REQUIRED`)은 그대로 둔다. §4.6 sync intake가 바깥 트랜잭션에 합류하는 계약이다.

**`RedisCheckoutLock`의 정적 호출을 줄인다.**
- `check(TransactionSynchronizationManager.isSynchronizationActive())`를 지운다. 어노테이션은 붙이지 않는다. 트랜잭션 밖에서 부르면 해제를 거는 `registerSynchronization`이 같은 조건(동기화 비활성)에서 `IllegalStateException`을 던진다. 잠금을 얻은 뒤라 잘못 부른 경우 그 키는 TTL 10초까지 남는다. 프로그래밍 오류에서만 생기고 테스트에서 드러나므로 감수한다.
- 잠금 토큰 `UUID.randomUUID()`를 생성자로 주입한 `foundation.IdentifierGenerator`의 `next()`로 바꾼다. 토큰은 요청마다 달라야 할 뿐이라 UUID v7이어도 해제 판정(토큰 일치)이 같다.
- `registerSynchronization`은 그대로 둔다(검토한 대안 참고).

**이벤트 발행을 모듈별 publisher로 옮긴다.**
- `order.internal.application.OrderEventPublisher`와 `payment.internal.application.PaymentEventPublisher`를 둔다. 둘 다 `ApplicationEventPublisher`를 생성자로 주입받는다.
- 이벤트 타입마다 구체 타입 파라미터를 받아 `publishEvent`에 넘기는 private 메서드를 둔다. IDE가 이 호출의 인자를 구체 타입으로 읽는다(`OrderPaidListenerIntegrationTest`의 같은 형태로 확인).
- 공개 메서드 `publishAll`은 `Transition`의 이벤트 목록을 받아 타입별로 그 메서드에 나눠 준다. 모듈이 내지 않는 타입이 오면 `IllegalArgumentException`으로 거절한다. 조용히 버리면 발행 기록 없이 사실이 사라진다.
- `order`: `OrderEvent.Created`, `OrderPaymentRequested`, `OrderPaid`. `payment`: `PaymentEvent.AttemptCreated`, `PaymentEvent.Declined`, `PaymentEvent.Unknown`, `PaymentApproved`.
- `OrderCommandFacade`와 `PaymentManagement`는 `ApplicationEventPublisher` 대신 자기 모듈 publisher를 주입받아 `publishAll(transition.events)`를 부른다. 발행은 지금처럼 Facade 트랜잭션 안이다.

계약: 이벤트 타입과 필드, `listener_id`, 오류 code, `CheckoutLock` port, Redis 키·TTL·대기 한도는 바뀌지 않는다. 트랜잭션 안에서 부른 `authorize`·`retryAuthorization`은 `IllegalTransactionStateException` 대신 호출자 트랜잭션과 따로 실행된다.

복구·관측성: 바뀌지 않는다.

## 제외
- `acquire` 대기 루프의 `System.nanoTime()`·`Thread.sleep`.
- 도메인 전이(`Order`, `PaymentAttempt`)의 `Transition` 이벤트 타입. 결제 전이는 서로 다른 타입을 함께 내므로 좁혀도 publisher의 타입별 분기가 필요하다.
- `fulfillment` 모듈. 이벤트를 발행하지 않는다.
- 리스너 선언(`@ApplicationModuleListener`)을 다른 애너테이션으로 바꾸는 것.

## 검토한 대안
- **`@Transactional(propagation = Propagation.MANDATORY)`로 잠금의 트랜잭션 전제를 선언**: `registerSynchronization`이 이미 하는 확인을 한 번 더 하고 전파 설정 종류를 늘린다.
- **`registerSynchronization`을 감싸는 타입을 새로 선언해 주입**: 사용자가 래퍼 타입을 새로 두지 말라고 결정했다. 호출이 한 곳뿐이다.
- **`@TransactionalEventListener`로 잠금 해제**: 잠금마다 이벤트를 발행하면 Spring Modulith가 `EVENT_PUBLICATION`에 잠금마다 행을 남긴다.
- **`registerSynchronization`을 획득 전에 불러 잘못된 호출에서 키를 남기지 않기**: 잠그지 못한 경우에도 해제가 불려 획득 여부를 담을 가변 변수나 헛된 해제 호출이 생긴다.
- **`authorize`에 `NEVER`를 유지**: 트랜잭션 안의 호출을 막아 주지만 전파 설정이 한 종류 늘고, 메서드가 트랜잭션 밖에서 돈다는 사실을 호출자 쪽 조건으로 적는다. `NOT_SUPPORTED`는 같은 사실을 메서드 쪽에서 보장한다.
- **적용 트랜잭션을 `REQUIRES_NEW`로 열기**: 적용이 호출자 트랜잭션과 갈리지만 provider 호출은 호출자 트랜잭션 안에 남아 §4.6을 지키지 못한다.
- **발행 지점을 람다(`forEach { publishEvent(it) }`)로 바꾸고 `Transition` 타입을 좁히기**: 주문 전이는 타입 하나라 풀리지만 결제 전이(생성과 0원 승인, 승인·거절·미확인)는 섞여 있어 `PaymentApprovedListener`가 계속 발행 지점을 못 찾는다.
- **publisher 안에서 스마트 캐스트한 값을 바로 `publishEvent`에 넘기기**: IDE가 스마트 캐스트 타입을 읽는지 확인하지 못했다. 선언된 파라미터 타입은 실제 파일에서 확인했다.

## 관련 문서
[도메인 모델](../wiki/domain-model.md) §4.3·§4.6, [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md). 잠금은 [payment-advisory-lock](2026-09-26-payment-advisory-lock.md), 승인의 트랜잭션 경계는 [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md)이 정했다.

공식 문서 대조:
- Spring Modulith 이벤트(https://docs.spring.io/spring-modulith/reference/events.html): 발행 기록은 `ApplicationEventPublisher`로 낸 이벤트를 트랜잭션 리스너 단위로 남긴다. 발행을 모듈 publisher로 감싸도 같은 `publishEvent`를 거치므로 기록 방식은 같다. `@ApplicationModuleListener`는 `REQUIRES_NEW` 트랜잭션을 함께 선언한다.
- Spring Framework `Propagation.NOT_SUPPORTED`(https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Propagation.html): 트랜잭션 없이 실행하고 현재 트랜잭션이 있으면 멈춘다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/internal/adapter/lock/RedisCheckoutLock.kt
- src/main/kotlin/com/resilientpos/payment/PaymentManagement.kt
- src/main/kotlin/com/resilientpos/payment/internal/application/PaymentEventPublisher.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/application/OrderEventPublisher.kt
- src/test/kotlin/com/resilientpos/payment/internal/adapter/lock/RedisCheckoutLockTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/application/PaymentAttemptCreationLockIntegrationTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/application/PaymentAuthorizationIntegrationTest.kt
- src/test/kotlin/com/resilientpos/payment/PaymentManagementTest.kt
- src/test/kotlin/com/resilientpos/order/OrderCommandFacadeLineLimitTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-28-checkout-lock-static-calls.md

## 인수 조건
1. 트랜잭션 밖에서 `CheckoutLock` 빈의 `lockUntilTransactionEnds`를 부르면 `IllegalStateException`이 난다.
2. 트랜잭션 안에서 `authorize`를 부르면 provider를 한 번 부르고, 그동안 DB 자원을 쥐지 않으며, 바깥 트랜잭션을 롤백해도 적용 결과(`APPROVED`)가 남는다.
3. main 코드의 명시적 전파 설정은 `NOT_SUPPORTED`뿐이다. `RedisCheckoutLock.kt`에는 `isSynchronizationActive`·`UUID.randomUUID` 호출과 `@Transactional`이 없다.
4. main 코드에서 `ApplicationEventPublisher.publishEvent`를 부르는 곳은 두 publisher뿐이고, 모든 호출의 인자가 구체 이벤트 타입으로 선언된 파라미터다.
5. 기존 테스트(이벤트 발행 순서와 내용, 발행 기록, 잠금, 동시성)가 깨지지 않는다.

## 검증
- `./gradlew test --tests 'com.resilientpos.payment.*'`와 `--tests 'com.resilientpos.order.*'`로 좁혀 돌린 뒤 `./gradlew test` 전체를 돌린다. Docker가 필요하다.
- 실패 시나리오: `authorize`가 `NEVER`면 인수 조건 2의 테스트가 `IllegalTransactionStateException`으로 실패한다. 해제 등록이 트랜잭션 확인 없이 바뀌면 인수 조건 1의 테스트가 예외 없이 끝나 실패한다.
- 인수 조건 3·4는 `grep`으로 확인한다.
- IDE 발행 지점: IntelliJ Spring 플러그인이 쓰는 판정(`publishEvent` 참조를 감싼 호출의 첫 인자 타입)을 inspection 스크립트로 흉내 내어, 지금 발행 지점은 `forEach`와 `KFunction`으로 읽히고 구체 타입 파라미터는 그 타입으로 읽히는 것을 확인했다. 변경 후 머지된 checkout에서 리스너 거터를 한 번 더 본다.
- `python3 scripts/check_pr.py --base 5eece85 --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md): 트랜잭션 밖이어야 하는 메서드는 `NOT_SUPPORTED`로 호출자 트랜잭션을 멈추고 거절하지 않는다는 규칙, 이벤트는 모듈 publisher가 구체 타입으로 발행한다는 규칙을 이벤트 발행 규칙 옆에 적는다.
- [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md): `ApplicationEventPublisher` 항목의 "이 프로젝트에서"에 모듈 publisher를 적고, 8절에 결정을 [합의]로 더한다. 이 문서는 Obsidian 볼트 원본을 가리키는 링크라 저장소 커밋에 들어가지 않는다.
- 요구사항 변화: 없다. 업무 동작과 이벤트 계약이 그대로이고, 트랜잭션 안에서 부른 승인·재결제가 거절 대신 따로 실행되는 것은 지금 호출자가 없는 경로다.

## 분할 계획
12개 파일, 추가와 삭제 합계 350줄 안쪽이다. 경고선(20개 파일/600줄)에 이르지 않는다.

## TDD 검증 기록
업무 동작은 바뀌지 않는 리팩터링이다. 바뀌는 두 경계(트랜잭션 밖 잠금 호출, 트랜잭션 안 승인 호출)는 통합 테스트로 RED→GREEN을 본다. publisher의 타입별 분기는 기존 Facade 테스트의 발행 검증이 덮는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 트랜잭션 밖에서 잠그면 거절한다 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentAttemptCreationLockIntegrationTest'`, 전파 설정 구현이 `IllegalTransactionStateException`을 던져 실패 | 같은 명령, 설정을 지운 뒤 통과 | `./gradlew test`, 246개 통과 |
| 트랜잭션 안에서 승인을 요청해도 바깥 트랜잭션과 따로 적용한다 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentAuthorizationIntegrationTest'`, `NEVER`가 `IllegalTransactionStateException`으로 거절해 실패 | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*'`, `NOT_SUPPORTED`로 바꾸고 publisher를 옮긴 뒤 통과 | `./gradlew test`, 246개 통과 |

## 결과
개발 전 spec을 독립 커밋으로 작성하고, 범위를 넓힐 때마다 구현 전에 spec만 다시 커밋했다. 구현 후 아래를 확인했다.

- `RedisCheckoutLock`에서 `check`를 지우고 어노테이션은 붙이지 않았다. 트랜잭션 밖 호출은 `registerSynchronization`이 `IllegalStateException`으로 거절한다. 토큰은 주입한 `IdentifierGenerator.next()`로 만든다.
- `PaymentManagement.authorize`·`retryAuthorization`을 `NOT_SUPPORTED`로 바꿨다. 트랜잭션 안에서 부른 `authorize`는 provider를 한 번 부르고, 호출 중 DB 자원을 쥐지 않으며, 바깥을 롤백해도 `APPROVED`가 남는다.
- `OrderEventPublisher`·`PaymentEventPublisher`를 더하고 두 Facade가 이를 거쳐 발행한다. 발행 순서·내용과 발행 기록 테스트는 고치지 않고 통과했다. Facade를 직접 만드는 단위 테스트 두 곳만 생성자 인자를 맞췄다.
- 명시적 전파 설정은 `NOT_SUPPORTED` 6곳(리스너 4, `PaymentManagement` 2)뿐이다. main 코드의 `publishEvent` 호출은 두 publisher의 private 메서드 7곳뿐이고 인자는 모두 구체 타입 파라미터다.
- IDE: 플러그인 판정을 흉내 낸 inspection 스크립트로, 이전 발행 지점은 `forEach`·`KFunction`으로 읽히고 구체 타입 파라미터(`OrderPaidListenerIntegrationTest`)는 `com.resilientpos.contract.OrderPaid`로 읽히는 것을 확인했다. IDE가 연 프로젝트가 main checkout이라 이 브랜치의 거터는 머지 뒤 확인한다.
- `./gradlew test`: 246개 통과, 실패 0.
