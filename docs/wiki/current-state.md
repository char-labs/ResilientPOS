# 현재 상태

## 실제 빌드 선언 (2026-09-15 확인)

| 항목 | 저장소에 선언된 상태 |
|---|---|
| 언어/JVM | Kotlin 2.4.10, Java toolchain 25 |
| 프레임워크 | Spring Boot 4.1.1, dependency-management plugin 1.1.7 |
| 빌드 | Gradle Wrapper 9.7.1, Kotlin DSL, 단일 root project |
| 서버/데이터 | Spring MVC, Spring Data JPA, Actuator, Jackson Kotlin |
| DB | PostgreSQL 전용. Flyway가 스키마를 소유하고 JPA는 validate만 한다 |
| 모듈 경계·이벤트 | Spring Modulith 2.1.1 (BOM + `starter-core`·`starter-jpa`, 테스트는 `starter-test`·`events-core`) |
| 식별자 | `uuid-creator` 6.1.1 (UUID v7) |
| 구조 검증 | Spring Modulith 경계 검증, ArchUnit 1.4.2 의존성 방향 가드 |
| 통합 테스트 | Testcontainers 2.0.5 PostgreSQL 18. `./gradlew test` 실행에 Docker가 필요하다 |
| 테스트 | Kotlin Test/JUnit 5, Spring Boot 테스트 starter, Fixture Monkey 1.2.3; 총 149개 (컨텍스트 1, 스키마 4, 모듈 구조 2, 의존성 방향 2, Money 25, 식별자 4, Order 19, PaymentAttempt 11, 결제 enum 이름 2, 자원 상한 설정 3, Facade line 상한 1, 오류 로그 요청 정보 6, OrderCommandFacade 11, RequestCheckout 통합 9, 버전 충돌 통합 1, 문장 수 통합 3, 이벤트 발행 기록 통합 7, API advice 9, OrderController 13, checkout Controller 5, 주문 API 통합 11) |
| 로컬 하네스 | Python 3 표준 라이브러리 |

근거: [build.gradle.kts](../../build.gradle.kts), [Gradle Wrapper](../../gradle/wrapper/gradle-wrapper.properties), [애플리케이션 설정](../../src/main/resources/application.yaml).
애플리케이션 설정에 PostgreSQL 접속과 Flyway, JPA validate가 있다. 접속 값은 환경 변수로 주입하며 비밀값을
하드코딩하지 않는다. `POSTGRES_PASSWORD`에는 기본값이 없다. 다만 **해석되지 않은 placeholder는 예외가 아니라 리터럴 문자열로 바인딩되므로**,
기동이 멈추는 실제 지점은 Hikari의 인증 실패이고 로그도 `password authentication failed`로 나온다. DB가 `trust` 인증이면 아무 값이나 통과해서 이 줄은 아무것도 막지 못한다. 알려진 한계다.
**요청 하나가 쓰는 자원에 상한이 있다.** 본문은 1 MiB, 문자열 하나는 65536자, 숫자 하나는 1000자까지 읽는다(Jackson 기본값은 차례로 무제한·1억·1000이다).
커넥션 대기 3초, 검증 1초, JDBC 연결 3초·소켓 30초, 쿼리 10초, 트랜잭션 30초다. 이 값들이 실제 빈에 반영되는지는 `ResourceLimitConfigurationTest`가 확인한다 —
프로퍼티 이름이 틀리면 Spring이 조용히 무시하므로 적었다는 사실만으로는 적용됐다고 말할 수 없다([request-resource-limits](../spec/2026-09-21-request-resource-limits.md)).
**톰캣 스레드 풀과 커넥션 풀의 크기는 아직 기본값이다.** 배포 환경이 없어 정할 근거가 없다. 상한을 두는 것과 크기를 정하는 것은 다른 결정이다.
속도 제한(rate limit)도 없다. 이 설정은 요청 **하나**의 상한이고 요청의 **빈도**는 다루지 않는다. 알려진 빈틈이다.
**`socketTimeout: 30`은 Flyway 마이그레이션에도 걸린다.** Flyway가 애플리케이션 `DataSource`를 그대로 쓰기 때문이다. 지금 마이그레이션은 전부 빈 테이블 DDL이라 문제가 없지만,
30초를 넘길 수 있는 마이그레이션(큰 테이블의 인덱스 생성, 백필)을 더하는 PR은 이 값을 함께 본다. 넘기면 커넥션이 끊겨 DDL 적용 여부가 모호해지고 `flyway repair`가 필요해진다. 알려진 빈틈이다.
**쿼리 10초는 읽기 문장에 붙는다.** flush 시점의 INSERT·UPDATE와 COMMIT은 `Query`를 거치지 않아 트랜잭션 30초와 소켓 30초가 덮는다. 커밋이 소켓 상한에 걸리면 성공 여부가 모호해지는데,
쓰기 endpoint 둘이 모두 멱등이라 재시도가 해소한다. 멱등이 아닌 쓰기를 더하는 PR이 이 전제를 다시 본다.
**문자열 하나의 상한(65536자)은 컬럼 상한(`product_code` 64, `product_name` 255)보다 훨씬 크다.** 그 사이 값은 여전히 DB 오류로 500이 된다. 위에 적은 알려진 빈틈과 같은 건이다.
**설정 파일이 하나라 운영용 3초가 테스트에도 그대로 걸린다.** CI가 흔들리면 그때 profile을 나눈다.
이 표는 빌드 선언을 확인한 결과이며, 의존성 다운로드·앱 실행 검증 결과를 의미하지 않는다.

## 계획과 구현 구분
- `com.resilientpos`에 애플리케이션 진입점이 있다. bounded context 7개(`store`, `terminal`, `order`, `payment`,
  `sync`, `reconciliation`, `fulfillment`)는 `package-info.java`의 `@ApplicationModule`로 선언했다. `order`에 첫 타입이 있고
  `store`·`terminal`은 ID 값 타입(`StoreId`, `TerminalId`)만 있고, `payment`에 `PaymentAttempt`가 생겼으며 나머지 3개(`sync`, `reconciliation`, `fulfillment`)는 비어 있다.
  `ModuleStructureTest`가 모듈 인식과 경계 위반을 검증한다. 위반 주입 시 `Module 'order' depends on non-exposed type ...`
  메시지로 실패하는 것을 확인했다. 순환도 잡는다(`Cycle detected: Slice order -> Slice payment -> Slice order`를 실제로 재현했다).
  **`@ApplicationModule(allowedDependencies = ...)`는 이 구성에서 위반을 잡지 못한다.** `payment`에 선언하고 허용하지 않은 모듈에 의존하게 만들어도 통과한다.
  어노테이션이 컴파일 결과에 들어간 것은 확인했으므로 선언 누락이 아니다. 의존 방향을 잠그는 수단으로 쓸 수 없다.
- Spring Modulith는 2.1.1로 도입했다. `spring-modulith-core` 2.1.1이 `spring-boot-autoconfigure` 4.1.1에 대해
  빌드되어 현재 Boot 버전과 세대가 맞는다. `starter-insight`는 아직 없다.
- Event Publication Registry(`starter-jpa`)를 도입했다. 트랜잭션 안에서 발행한 이벤트는 그 이벤트를 받는 트랜잭션 이벤트 리스너마다 `event_publication`에 한 행씩, 업무 상태와 같은 커밋으로 기록된다.
  리스너가 성공하면 `completion_date`와 `status=COMPLETED`가 찍히고(완료 처리 UPDATE, 행은 남는다) 실패하면 `status=FAILED`인 미완료 기록으로 남는다. 업무 트랜잭션이 롤백되면 기록도 리스너 호출도 없다.
  **받는 리스너가 없는 이벤트는 기록되지 않는다.** 프로덕션 리스너가 아직 없어서 지금 실제로 기록되는 이벤트는 없고, 동작은 테스트 전용 리스너로 검증한다.
  Modulith의 자동 구성이 `@EnableAsync`를 켜므로 `@ApplicationModuleListener`는 Spring Boot의 기본 task executor에서 비동기로 실행된다. 업무 요청의 응답은 리스너를 기다리지 않는다. 그 실행기는 코어 8, 큐 무제한이다. 동시에 도는 리스너는 8개가 상한이고 밀린 작업은 메모리에 쌓인다. 종료할 때 진행 중인 리스너를 2초 기다리고(Modulith가 바꾸는 기본값) 그 안에 시작하지 못한 작업은 실행되지 않은 채 미완료 기록으로 남는다. 풀·큐·거절 정책은 provider를 호출하는 리스너를 넣는 `payment` PR이 정한다.
  완료 처리 방식(`completion-mode: update`)과 재기동 시 재발행 끔은 기본값과 같지만 `application.yaml`에 적어 두었다.
  이벤트는 JSON으로 저장된다. value class ID는 UUID 문자열, `Money`는 `{"amount":3500,"currency":"KRW"}` 모양이고 `30.00`처럼 끝자리가 0인 금액도 자릿수를 잃지 않고 복원된다.
  **미완료 이벤트를 다시 보내는 절차가 없다.** 재기동 시 재발행은 꺼져 있고(인스턴스가 여럿이면 중복 재발행된다) 재전송 주기도 없다. 완료 행을 지우는 정리 작업도 없다. 첫 실제 리스너가 생기는 `payment` PR이 정한다. 알려진 빈틈이다([event-publication-registry](../spec/2026-09-21-event-publication-registry.md)).
- Kafka, Redis, Kotlin JDSL, OpenTelemetry는 참고 문서의 설계 방향이며 현재 빌드 의존성에는 없다.
- `order` 모듈은 공개 계약(`OrderCommandFacade` 클래스, `CreateOrderCommand`·`CreateOrderResult`, `OrderId`·`ClientOrderId`,
  `OrderStatus`, `OrderEvent`·`OrderCreated`)과 `internal/domain`의 `Order`·`OrderLine`이 있다. `Order.create`가 생성 불변식
  (line 1개 이상 500개 이하, 상품 코드·상품명 blank 아님, 수량 1 이상, 단가 0 이상, 통화 일치)을 검증하고 `Transition`을 돌려준다([order-domain-model](../spec/2026-09-16-order-domain-model.md)).
  상태는 `OPEN`과 `CHECKOUT_REQUESTED` 둘이다. `order.requestCheckout(ids, now)`가 `OPEN` 주문의 합계를 다음 회차 `Checkout`(`id`, `checkoutNo`, `amount`)으로 확정하고
  `CHECKOUT_REQUESTED`로 전이하면서 `OrderPaymentRequested`(`orderId`·`storeId`·`terminalId`·`checkoutNo`·`amount`)를 만든다. 이미 요청된 주문이면 같은 주문과 빈 이벤트를 돌려준다.
  합계가 0원이어도 똑같이 전이한다. store 정책과 정책 버전은 아직 없다([order-request-checkout-domain](../spec/2026-09-21-order-request-checkout-domain.md)).
  `OrderCommandFacade.requestCheckout(RequestCheckoutCommand)`이 `@Transactional` 안에서 주문 행을 잠그고 읽어(`findByIdForUpdate`, Hibernate가 PostgreSQL에 보내는 SQL은 `select ... for no key update`다) 전이를 저장하고
  `OrderPaymentRequested`를 발행한 뒤 `RequestCheckoutResult`(`orderId`·`status`·`checkoutNo`·`amount`)를 돌려준다. 없는 주문은 `ORDER_NOT_FOUND`(`NOT_FOUND`의 첫 사용처)다.
  순차 재시도와 겹친 요청 모두 기존 결과를 받는다. 겹친 요청은 앞 커밋을 기다린 뒤 `CHECKOUT_REQUESTED`를 읽는다. 잠금 대기는 3초로 제한한다(`jakarta.persistence.lock.timeout` 힌트, Hibernate가 조회 앞뒤로 `set local lock_timeout`을 걸고 되돌린다). 넘으면 `PessimisticLockingFailureException` 계열 예외로 실패하고 아무것도 바꾸지 않는다. HTTP에서는 `409 CONCURRENT_REQUEST`다. `findByIdForUpdate`는 트랜잭션에서 그 주문의 첫 접촉이어야 한다. 먼저 일반 조회한 뒤 그 사이에 주문이 바뀌었으면 낡은 값을 받지 않고 낙관적 잠금 예외로 실패한다. `V003__order_request_checkout.sql`이 `t_order_checkout`
  (`id`, `order_id`, `checkout_no`, `amount`, unique `(order_id, checkout_no)`)을 만들고 통화는 `t_order.currency`에서 읽는다. checkout 행은 넣기만 하고 갱신·삭제하지 않는다([order-request-checkout-persistence](../spec/2026-09-21-order-request-checkout-persistence.md)).
  endpoint는 `POST /v1/orders/{orderId}/checkout`이다. 요청 본문이 없고 200과 `data{orderId, status, checkoutNo, amount, currency}`를 돌려준다. `amount`는 decimal 문자열이다.
  첫 요청과 재요청이 같은 200과 같은 본문이다. 없는 주문은 `404 ORDER_NOT_FOUND`, UUID 형식이 아닌 `orderId`는 `400 BAD_REQUEST`다.
  **인가가 없다.** 단말 인증이 아직 없어서 `orderId`만 알면 다른 매장의 주문도 `CHECKOUT_REQUESTED`로 전이시킬 수 있다. 전이를 되돌리는 endpoint가 없어서 잘못 촉발된 checkout은 복구할 수 없다. **단말 인증과 주문의 매장·단말 소유권 검사가 들어오기 전에는 이 API를 외부에 노출하지 않는다.** 인증만 넣고 소유권 검사를 하지 않으면 빈틈은 남는다. 알려진 빈틈이다([order-request-checkout-api](../spec/2026-09-21-order-request-checkout-api.md)).
  `OrderCommandFacade`가 `@Transactional` 안에서 같은 `(terminalId, clientOrderId)`의 주문을 찾으면 `order.verifySamePayload(command)`로
  payload를 확인한 뒤 기존 결과를 돌려주고, 아니면
  `Order.create` → `OrderRepository.save` → `OrderCreated` 발행 순으로 진행한다. JPA adapter는
  `internal/adapter/persistence/command`에 있고 `V002__order_create_order.sql`이 `t_order`·`t_order_line`을 만든다
  ([order-create-order-persistence](../spec/2026-09-16-order-create-order-persistence.md)).
  `verifySamePayload`는 저장된 주문과 command의 `storeId`·`currency`·line(`productCode`·`productName`·`unitPrice`·`quantity`)을 직접 비교한다.
  line 순서와 금액 표기 차이는 같은 payload이고, 다르면 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`을 던진다. 최초 요청을 따로 저장하는
  컬럼은 없다. line·`storeId`·`currency`를 바꾸는 명령이 생기면 현재 상태가 최초 요청과 달라지므로 그 PR이 최초 요청 기록을 도입한다
  ([order-idempotency-conflict](../spec/2026-09-20-order-idempotency-conflict.md)).
  첫 공개 endpoint `POST /v1/orders`가 `internal/adapter/web`의 `OrderController`에 있다. 요청 본문을 `CreateOrderCommand`로 옮겨 Facade를 부르고
  201과 `data{orderId, status}`를 돌려준다. 같은 `(terminalId, clientOrderId)` 재시도도 201이고 `orderId`가 같다. `status`는 주문의 현재 상태라서 checkout을 요청한 뒤의 재시도는 `CHECKOUT_REQUESTED`를 받는다. 형식 오류는 타입이 있는 DTO의
  Jackson 역직렬화가 거절해 `400 BAD_REQUEST`가 되고 업무 규칙은 도메인이 거절한다. 같은 키에 다른 payload가 오면 `409 IDEMPOTENCY_CONFLICT`다.
  빈 문자열이거나 공백뿐인 `productCode`·`productName`은 `OrderLine`이 `BLANK_PRODUCT_CODE`·`BLANK_PRODUCT_NAME`(400)으로 거절하고 값은 다듬지 않는다. 이미 주문이 있는 키로 오면 멱등 판정이 먼저라서 409다([order-line-blank-product](../spec/2026-09-21-order-line-blank-product.md)).
  단가를 지수 표기(`1E+100000`)로 보내면 `400 INVALID_MONEY_AMOUNT`다. 거절 비용은 지수 크기와 무관하다. `currency`가 `XAU`·`XDR`·`XXX`처럼 금액을 표현할 수 없는 통화면 `400 UNSUPPORTED_CURRENCY`다([money-input-boundary](../spec/2026-09-21-money-input-boundary.md)).
  **매장이 어떤 통화로 영업할지 정하는 allow-list는 없다.** `Money`가 표현할 수 있는 통화(`defaultFractionDigits`가 0 이상)면 무엇이든 받는다. 알려진 빈틈이다.
  금액 상한은 개별 금액과 합계를 모두 막지만, **합계가 범위를 넘는 주문이 이미 저장돼 있다면 그 주문은 여전히 고착이다.** 조회와 주문 생성 재시도는 되고 checkout만 `400 INVALID_MONEY_AMOUNT`를 받으며 `OPEN`에 남는다(`Order.restore`는 합계를 평가하지 않는다). 배포된 환경이 없어서 지금 그런 행은 없다.
  `productCode` 64자·`productName` 255자를 넘는 입력이 DB 오류로 `500 INTERNAL_ERROR`가 되는 것과 같은 키의 동시 요청이 unique 제약 위반으로 끝나는 것은 알려진 빈틈이다. 주문을 soft delete하면 같은 키의 재시도가
  삭제된 주문을 찾지 못하고 unique 제약에 걸리는데, 지금은 주문을 삭제하는 코드가 없다([order-create-order-api](../spec/2026-09-20-order-create-order-api.md)).
- `foundation` 모듈에 `Money`, `IdentifierGenerator`/`UuidV7IdentifierGenerator`, 모델링 원시 타입 `DomainEvent`·`Transition`,
  도메인 규칙 위반을 알리는 `DomainException(code, message, kind)`과 `DomainErrorKind`(`INVALID`·`NOT_FOUND`·`CONFLICT`·`UNPROCESSABLE`)가 있다. 프레임워크 의존성은 없다.
  `Order`·`OrderLine`·`Money`는 규칙 위반을 `DomainException`으로만 던진다. `code`는 API 오류 응답과 로그 `errorCode`에 쓰는 안정적인
  식별자다. `kind`는 HTTP 숫자가 아닌 업무 의미이고 기본값은 `INVALID`다. 입력 거절 코드 10개는 `INVALID`이고 `IDEMPOTENCY_CONFLICT`가 `CONFLICT`의 첫 사용처다([foundation-domain-exception](../spec/2026-09-19-foundation-domain-exception.md)).
  `Money`는 통화의 표준 소수 자릿수를 canonical scale로 쓰고, scale 초과 입력과 통화 불일치 연산을 거절한다.
  **정수부가 15자리를 넘는 금액도 `INVALID_MONEY_AMOUNT`로 거절한다.** 금액을 담는 컬럼이 `numeric(19,4)`라 19 − 4 = 15자리가 상한이고, 상수와 근거는 `Money.MAX_INTEGER_DIGITS`에 있다.
  검사는 `init`에 있어서 생성자·`of`·`copy`·연산이 모두 거친다. `Order.create`는 lazy인 합계를 생성 시점에 한 번 평가한다. 이 평가가 없으면 단가는 범위 안이고 합계만 넘는 주문이 커밋된 뒤
  checkout에서야 드러나 복구할 수 없었다. 실제로 그 고착을 재현한 뒤 고쳤다([money-amount-limit](../spec/2026-09-21-money-amount-limit.md)).
  **`of`는 값을 펴기 전에 메타데이터(`precision`·`scale`)로 판정한다.** 거절 비용이 입력의 크기를 따라가지 않게 하려는 것이고, 이 순서를 뒤집으면 `1E+100000` 하나에 수백 ms가 돌아온다.
  입구는 둘이다. `of(BigDecimal, Currency)`가 본체이고 `of(String, Currency)`는 파싱 실패를 `INVALID_MONEY_FORMAT`으로 바꿔 넘긴다. `defaultFractionDigits`가 음수인 통화는
  canonical scale을 세울 수 없어 `UNSUPPORTED_CURRENCY`로 거절하며, 판정을 `init`과 `of`가 공유해 생성자·`copy`·`zero`도 같이 막힌다([money-input-boundary](../spec/2026-09-21-money-input-boundary.md)).
  나눗셈·퍼센트 할인·세금 계산은 없다. 반올림 정책은 매장 규칙이므로 `store` 정책과 checkout에서 다룬다.
- `payment` 모듈에 `PaymentAttempt` aggregate가 있다. 공개 계약은 `CreatePaymentAttemptCommand`·`CreatePaymentAttemptResult`,
  `PaymentId`, `PaymentAttemptStatus`, `sealed interface PaymentEvent`와 `PaymentAttemptCreated`·`PaymentApproved`다. `Order`와 별개 aggregate라 `orderId`로만 참조하고 사실은 이벤트로 건너간다.
  **Facade는 아직 없다.** 도메인 모델 §4.4가 Facade를 구체 클래스로 두고 구현이 둘일 때 인터페이스를 뽑으라고 정했으므로, 구현이 생기는 영속화 PR이 함께 만든다.
  **`payment`는 `order`를 import하지 않는다.** 공개 타입이 `orderId`를 `order.OrderId`가 아니라 raw `UUID`로 든다. `OrderId`를 쓰면 `order`가 `PaymentApproved`를 소비하는 순간
  `order -> payment -> order` 순환이 되어 Modulith 검증이 실패한다. 실제로 재현한 뒤 이 방향으로 정했다. `order`는 받은 값을 `OrderId`로 감싼다.
  **상태는 `REQUESTED`·`APPROVED`·`DECLINED`·`UNKNOWN` 넷이다.** 원문 세 개가 다른 이름을 써서(`CONFIRMED`·`PROCESSING`·`PENDING`) 용어집의 `PaymentApproved` 줄기로 통일했다.
  `UNKNOWN`은 실패가 아니다. 새 승인 요청으로 재시도하지 않고 조회와 reconciliation으로 수렴한다.
  `PaymentAttempt.create`가 생성 불변식(금액 0 이상은 `NEGATIVE_PAYMENT_AMOUNT`, checkout 회차 1 이상은 `INVALID_CHECKOUT_NO`, 시도 회차 1 이상은 `INVALID_ATTEMPT_NO`)을 검증하고 `Transition`을 돌려준다.
  **한 주문에 시도가 여럿 달린다.** 승인이 거절되면 다음 시도가 생기고 취소·환불도 각각 별도 시도다. `(orderId, attemptNo)`가 자연 키이고 회차가 다르면 같은 주문이어도 다른 행이 된다.
  `attemptNo`는 명령이 아니라 저장된 이력에서 정해지는 값이라 `create`의 별도 인자다. 부르는 쪽이 마지막 회차 + 1을 넘긴다(개수 + 1이 아니다. 중간 회차가 지워지면 번호가 겹친다). 채번 구현은 repository가 생기는 PR이 맡는다.
  **0원 주문은 provider를 부르지 않고 `APPROVED`로 만들며 `PaymentAttemptCreated`와 `PaymentApproved`를 함께 낸다.** 시도를 기록으로 남겨야 0원 주문도 조회 경로가 같다.
  provider를 부르지 않았으므로 `providerReference`가 `null`이고 그 `null`이 "provider가 모르는 승인"이라는 사실을 그대로 나타낸다.
  **시도 회차는 언제나 1이다.** 초기 정책이 주문당 시도 한 건이고 `(orderId, attemptNo)` unique가 그것을 강제한다. 채번은 repository가 생기는 PR이 정한다.
  **Facade·영속화·`OrderPaymentRequested` 리스너와 외부 provider 경계(`PaymentGateway` 포트, `FakePaymentGateway`)는 다음 PR이 함께 만든다.** 포트는 부르는 쪽과 같은 PR에 둬야 모양을 확인할 수 있다.
  **이벤트 중복 전달은 `(orderId, attemptNo)`가 막는 대상이 아니다.** 그 키는 회차 채번의 경합을 막는다. 중복 전달은 Event Publication Registry가 `(이벤트, 리스너)`마다 완료를 기록해 막는다.
  재발행을 켜면 at-least-once가 되므로 그때는 업무 수준 멱등 근거가 따로 필요하다. 알려진 빈틈이다([payment-domain-model](../spec/2026-09-22-payment-domain-model.md)).
- `platform` 모듈은 `IdentifierGenerator`(UUID v7)와 `Clock`(UTC) 빈을 등록하는 `PlatformConfiguration`과 엔티티 공통 컬럼
  (`created_at`·`updated_at`·`deleted_at`, Hibernate `@SoftDelete`)을 주는 `BaseEntity`를 가진다. `OrderEntity`·`OrderLineEntity`·`OrderCheckoutEntity`가 상속한다. 모듈은 9개다.
  낙관적 잠금 `@Version`은 `BaseEntity`에 없고 필요한 엔티티가 직접 선언한다. 지금은 `OrderEntity`뿐이라 `version` 컬럼은 `t_order`에만 있다. `@Version`이 없는 새 자식 행은
  `OrderPersistenceAdapter`가 `EntityManager.persist`로 넣고, 어댑터의 `save`는 `@Transactional`로 Facade의 트랜잭션에 합류한다([base-entity-explicit-version](../spec/2026-09-21-base-entity-explicit-version.md)).
  `created_at`·`updated_at`은 Hibernate가 JVM에서 만드는 애플리케이션 시각이고(`SourceType.VM`) `deleted_at`만 DB `current_timestamp`다. 한 INSERT의 두 시각은 같지 않을 수 있어서
  갱신된 적 없는 주문 행은 `version = 0`으로 판정한다. `SourceType.DB`였을 때는 Hibernate 7.4.5가 UPDATE의 generated 값을 읽느라 갱신 행 수 검사를 건너뛰어서, 커밋 시점의 버전 충돌이
  낙관적 잠금 예외가 아니라 `JpaSystemException`으로 나왔다. 지금은 `OptimisticLockingFailureException` 계열로 나오고 HTTP에서는 아래 `CONCURRENT_REQUEST` 매핑을 타서 409다([commit-version-conflict-optimistic-lock](../spec/2026-09-21-commit-version-conflict-optimistic-lock.md)).
  HTTP 응답 공통 모양도 여기에 있다. `ApiResponseBodyAdvice`가 `com.resilientpos` Controller의 본문을 `ApiResponse`(`result`·`data`·`error`)로
  감싸고, `ApiControllerAdvice`가 Spring MVC 표준 예외와 처리하지 못한 예외를 `ErrorResponse`(`errorCode`·`message`)로 바꾼다.
  `ErrorType`은 HTTP 공통 오류 5개만 가진다. 다섯째 `CONCURRENT_REQUEST`(409)는 Spring의 `ConcurrencyFailureException` 계열(잠금 대기 초과, 버전 충돌)을 받는다. 5xx로 집계하지 않는다. 고정 문구는 재시도를 약속하지 않는다. 다시 보내도 되는지는 그 endpoint가 멱등인지에 달려 있고, 지금의 쓰기 endpoint 둘(`POST /v1/orders`는 `clientOrderId`로, checkout은 상태로)은 멱등이다. 멱등이 아닌 쓰기 endpoint를 더하는 PR이 이 전제를 다시 본다. 409에는 재시도하면 안 되는 `IDEMPOTENCY_CONFLICT`도 있으므로 단말은 상태 코드가 아니라 `errorCode`로 분기한다. 이 계열에는 deadlock 패자와 직렬화 실패도 들어 있다. 단말의 중복 요청이 아니라 서버 코드의 잠금 순서 문제일 수 있으므로 WARN 로그의 `errorCode=CONCURRENT_REQUEST`에서 따로 본다. 커밋 시점의 버전 충돌도 이 매핑에 닿는다. 영속 계층이 `ObjectOptimisticLockingFailureException`을 내는 것은 `OrderVersionConflictIntegrationTest`가, 그 계열이 409가 되는 것은 `ApiAdviceTest`가 고정한다. 지금은 잠금 없이 `@Version`에 기대는 endpoint가 없어서 HTTP까지 잇는 테스트는 없다. unique 제약 위반(`DataIntegrityViolationException`)은 이 계열이 아니라서 계속 500이다. 응답 메시지는 고정 문구이고 예외 메시지는 로그에만 남는다. `DomainException`만 예외로,
  `kind`가 정한 상태(`INVALID` 400, `NOT_FOUND` 404, `CONFLICT` 409, `UNPROCESSABLE` 422)와 함께 예외의 `code`·`message`가 `error`에 그대로 나간다.
  매핑은 else 없는 `when`이라 `kind`를 추가하고 매핑을 빠뜨리면 컴파일되지 않는다. Bean Validation은 쓰지 않는다([api-response-advice](../spec/2026-09-19-api-response-advice.md)).
  **오류 로그에는 요청 정보가 함께 남는다.** 형식은 `method=POST uri=/v1/orders errorCode=... : ...`이고 도메인 규칙 위반·표준 예외·처리하지 못한 예외 세 자리 모두에 적용된다.
  경로 변수가 URI에 그대로 있어서 `POST /v1/orders/{orderId}/checkout`의 주문 식별자는 URI만으로 드러난다. advice가 endpoint별로 식별자를 뽑지 않는 이유다.
  **쿼리 문자열은 남기지 않는다.** 지금 쿼리 파라미터를 쓰는 endpoint가 없고, 나중에 생겼을 때 개인정보가 로그로 흘러 들어가는 경로가 된다.
  **요청 상관관계 ID(correlation id)와 접근 로그는 없다.** 구조화 로그(JSON)도 아직이다. 알려진 빈틈이다([error-log-request-context](../spec/2026-09-21-error-log-request-context.md)).
- 제공된 요구사항 문서 7개는 [원문 목록](../references/README.md)에 보관했다. 요구사항 재정리와 원문 간 정합성 검토는 아직 수행하지 않았다.
- 애플리케이션 작업에는 [기존 설계 원칙](../../README.md#engineering-principles)을 참고하되 도입 완료 여부는 코드로 확인한다.
- 개발 기준은 [개발 규칙](development-workflow.md)이다. 공통 LLM 지침과 로컬 검사로 운영한다.

## 테스트 개발 방식
비즈니스·도메인·Controller는 [TDD 전략](testing-strategy.md)을 따른다. Repository 자체 테스트는 대상에서 제외한다.
TDD 스킬과 Claude/Codex 에이전트를 구성했다. 정본은 Obsidian 볼트에 있고 저장소는 `.claude`와 `.codex` 심볼릭 링크로 연결하며 둘 다 추적하지 않는다. 링크 생성은 `make bootstrap`이 담당한다. Fixture Monkey는 `fixture-monkey-starter-kotlin` 1.2.3으로 도입했고 `OrderFixtures`가 첫 사용처다. REST Docs는 `spring-boot-starter-restdocs`와 `spring-restdocs-mockmvc`로 도입했고 `OrderControllerTest`가 첫 사용처다. 스니펫은 `build/generated-snippets`에 생기며 Asciidoctor HTML 빌드는 아직 없다.

## Gradle 프로젝트 구조와 분할 조건
단일 Gradle 프로젝트를 유지한다. [개발 가이드 1절](../references/03_Development_Guide.md)은 패키지 경계가 안정되고 독립
build/test/dependency 또는 외부 contract artifact가 실제로 필요할 때만 subproject를 추가하라고 정한다. 현재 모듈 7개 중 다섯이 비어 있거나 ID 타입만 가지고 있고
전체 테스트가 하나의 태스크로 끝난다. 지금 나누면 담길 코드보다 빌드 코드가 많아진다.

아래 중 하나라도 충족하면 별도 PR로 분할을 검토한다.
- `pos-client`처럼 별도 실행체가 생겨 domain이나 contract 코드를 공유해야 한다.
- `contracts/`의 OpenAPI·AsyncAPI 아티팩트를 외부에 배포해야 한다.
- 한 모듈에만 필요한 의존성(Kafka, Redis adapter 등)이 전체 classpath로 새는 것이 실제 문제가 된다.
- 빌드·테스트 시간이 길어져 모듈 단위 증분 빌드가 필요하다.

분할을 미뤄도 경계가 무너지지 않는다. Modulith 검증이 모듈 간 internal 참조와 순환을, ArchUnit 가드가 프레임워크
의존을 막는다. 그래서 나중 분할은 패키지 이름이 바뀌지 않는 파일 이동이다.

## 의존성 방향 가드
`DependencyDirectionTest`가 `foundation`의 프로덕션 코드에서 Spring, JPA, Servlet, Jackson, Kafka 의존을 막는다.
`package-info`의 Modulith 애너테이션은 코드가 아니라 모듈 메타데이터라 예외로 둔다. 같은 애너테이션이 실제 타입에
붙으면 위반으로 잡힌다.

도메인 레이어 규칙도 같은 파일에 있다. `..internal.domain..`의 프로덕션 코드는 위 프레임워크 패키지와 같은 모듈의
`..internal.adapter..`·`..internal.application..`에 의존하지 않는다. Modulith 검증은 모듈 사이만 보므로 모듈 안의 레이어
방향은 이 규칙이 지킨다. `Order`에 `@Component`를 임시로 붙이면 실패하는 것을 확인했다.

## 데이터베이스와 스키마 소유
PostgreSQL이 유일한 엔진이다. H2는 제거했다. 테스트가 운영과 다른 엔진에서 돌면 unique 제약, 타입, 잠금 동작처럼
정확성의 마지막 방어선을 검증하지 못한다.

스키마의 유일한 소유자는 `src/main/resources/db/migration`의 Flyway 마이그레이션이다. JPA는 `validate`라서
애플리케이션이 테이블을 만들거나 바꾸지 않는다. 마이그레이션이 실패하면 애플리케이션이 뜨지 않는다.

파일명은 `V(번호)__(소유 모듈)_(변경 내용).sql`이다. `V001__baseline.sql`은 의도적으로 비어 있고, 업무 테이블은 각
모듈이 후속 마이그레이션으로 추가한다. 첫 업무 마이그레이션은 `V002__order_create_order.sql`(`t_order`, `t_order_line`)이고 `V003__order_request_checkout.sql`이 `t_order_checkout`을 더했다. `V004__platform_event_publication.sql`은 업무 테이블이 아니라 Modulith의 `event_publication`이다. `V005__order_drop_child_version.sql`은 `t_order_line`과 `t_order_checkout`의 `version` 컬럼을 없앴다.
스키마를 바꾸는 PR은 [도메인 모델](domain-model.md)의 "스키마 변경 검토" 절 질문(필요성·정합성·반영)에 spec에서 답한다.

통합 테스트는 Testcontainers로 실제 PostgreSQL 18을 띄운다. **`./gradlew test`에 Docker가 필요하다.** 컨테이너는
JVM당 한 번만 뜨고 Spring 컨텍스트 캐시와 수명을 맞춘다.
