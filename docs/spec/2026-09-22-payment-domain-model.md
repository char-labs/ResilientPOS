# PR spec: payment의 PaymentAttempt aggregate와 PaymentGateway 포트

## 목적
두 번째 업무 도메인을 `payment` 모듈에 만든다. `PaymentAttempt` aggregate가 결제 시도 생성 불변식을 소유하고, `CreatePaymentAttempt` 명령의 공개 계약과 결제 상태 어휘를 고정한다. 외부 provider를 부르는 경계도 `PaymentGateway` 포트로 이 PR에서 정의하고, 구현은 `FakePaymentGateway` 하나만 둔다. 실제 provider(토스페이먼츠 등) 연동은 별도 PR이 붙인다.

## 범위
소유 모듈은 `payment`다. 결제 정본 상태를 바꾸는 유일한 모듈이라고 [도메인 모델](../wiki/domain-model.md) §3이 정해 뒀다.

**상태 어휘를 고정한다.** 원문 세 개가 서로 다른 이름을 쓴다. 청사진은 `PaymentApproved`·`PaymentDeclined`·`PaymentUnknown`, PRD 본문은 `PROCESSING → UNKNOWN → RECONCILING → CONFIRMED`, 외부 연동 전략은 `PENDING/UNAVAILABLE`이다. 용어집이 이미 `PaymentConfirmed`를 금기어로 두고 `PaymentApproved`로 정했으므로 그 줄기를 따른다.

| 상태 | 뜻 |
|---|---|
| `REQUESTED` | 시도를 만들었고 provider 결과를 아직 적용하지 않았다. `order`의 `CHECKOUT_REQUESTED`와 같은 어법이다 |
| `APPROVED` | provider가 승인했거나, 0원이라 provider를 부르지 않고 승인으로 기록했다 |
| `DECLINED` | provider가 거절했다. 결과를 받았다는 점에서 `UNKNOWN`과 다르다 |
| `UNKNOWN` | provider 결과를 확인하지 못했다. **실패가 아니다.** 새 승인 요청으로 재시도하지 않고 조회와 reconciliation으로 수렴한다 |

네 값을 모두 선언한다. 이 PR이 만드는 전이는 `REQUESTED`와 `APPROVED`뿐이지만, 상태 집합 자체가 이 PR이 고정하는 계약이다. 다만 이 PR에는 `PaymentAttemptStatus`를 받는 `when`이 없어서 exhaustive 검사의 보호는 아직 없다. 그래서 이름과 순서를 단언하는 테스트로 대신 고정한다.

**생성 불변식을 aggregate가 소유한다.** 금액은 음수일 수 없고 `checkoutNo`는 1 이상이다. `attemptNo`는 명령이 주는 값이 아니라 aggregate가 정하므로 검증 대상이 아니다. 금액의 자릿수·범위 검증은 `Money`가 이미 한다.

**0원은 provider를 부르지 않고 승인으로 기록한다.** [도메인 모델](../wiki/domain-model.md) §3이 정한 규칙이다. `order`가 0원 주문도 똑같이 checkout을 확정하고 `OrderPaymentRequested`를 내므로, `payment`가 여기서 거절하면 전액 할인·프로모션 주문이 결제 단계에서 막힌다. 생성 결과가 `APPROVED`이고 이벤트를 두 개(`PaymentAttemptCreated`, `PaymentApproved`) 낸다. 시도를 기록으로 남겨야 주문·결제·영수증 조회가 0원 주문에서도 같은 경로로 보인다.

**`attemptNo`는 1로 고정한다.** 청사진이 "초기 정책은 주문당 `PaymentAttempt` 한 건"이라고 정했다. `(orderId, attemptNo)` unique 제약이 그 정책을 강제한다. 회차를 세는 일은 저장된 시도를 읽어야 가능한데 이 PR에는 repository가 없다. 취소를 별도 시도로 기록하거나 분할 결제를 다루는 PR이 채번 규칙을 정한다.

**`payment`는 `order`를 import하지 않는다.** 공개 타입이 `orderId`를 `order.OrderId`가 아니라 raw `UUID`로 든다. `OrderId`를 쓰면 `order`가 `PaymentApproved`를 소비하는 순간 `order -> payment -> order` 순환이 되어 Modulith 검증이 실패한다. 타입 안전성은 `payment` 경계 안에서만 잃고 `order`는 받은 값을 `OrderId`로 감싼다.

**`PaymentGateway` 포트를 정의한다.** 외부 연동 전략이 정한 경계다. provider SDK DTO·HTTP 상태·비밀값을 노출하지 않는다. 이 PR은 `authorize` 하나만 둔다. 결과는 승인·거절·미확인 세 갈래를 가진 sealed 타입이라 "timeout은 실패가 아니다"가 타입으로 드러난다.

원문의 포트는 `suspend` 함수인데 **블로킹으로 만든다.** 이 저장소에 코루틴 의존성이 없고 Spring MVC가 블로킹이다. 코루틴을 들이는 것은 이 PR의 목표가 아니다.

**`FakePaymentGateway`를 유일한 구현으로 둔다.** 외부 연동 전략이 Fake PG를 P0로 두고 "항상 유지"라고 적었다. 실제 provider가 붙어도 지우지 않는다.

계약: 새 `DomainException` code 두 개가 나중에 응답 본문의 `error.errorCode`로 나간다.

| code | 의미 |
|---|---|
| `NEGATIVE_PAYMENT_AMOUNT` | 결제 금액이 음수다 |
| `INVALID_CHECKOUT_NO` | checkout 회차가 1 미만이다 |

복구: 둘 다 요청 자체가 잘못된 경우라 재시도로 풀리지 않는다.

관측성: 이 PR은 저장·외부 호출·트랜잭션이 없어 로그 속성과 재시도 경로가 생기지 않는다. `FakePaymentGateway`도 부르는 쪽이 없다.

## 제외
- Facade 자체. [도메인 모델](../wiki/domain-model.md) §4.4가 "Facade는 인터페이스를 따로 두지 않는 구체 클래스이고 구현이 둘이 될 때 인터페이스를 뽑는다"고 정했다. 구현도 호출부도 없는 상태에서는 시그니처가 맞는지 확인할 수단이 없다. `PaymentAttemptRepository` 포트와 JPA adapter, `t_payment_attempt` 마이그레이션과 함께 다음 PR이 만든다.
- `t_payment_attempt.status`의 저장 방식. 다음 PR은 `EnumType.STRING`과 varchar를 쓴다. JPA 기본값인 ORDINAL은 값이 늘 때 기존 행의 뜻이 바뀐다.
- `OrderPaymentRequested`를 받는 `@ApplicationModuleListener`와 그에 딸린 재시도·풀·종료 정책. 그 PR이 [현재 상태](../wiki/current-state.md)에 적힌 Event Publication Registry의 빈틈도 함께 닫는다.
- provider 결과를 적용하는 전이(`APPROVED`·`DECLINED`·`UNKNOWN`)와 그때 나는 `PaymentDeclined`·`PaymentUnknown` 이벤트. 상태 값만 이 PR이 선언하고 전이는 결과를 받는 PR이 만든다.
- `PaymentGateway`의 `cancel`과 `findByMerchantReference`. 각각 취소 시도와 미확인 수렴을 다루는 PR이 그 전이와 함께 더한다. 부르는 쪽 없이 먼저 만들지 않는다.
- provider idempotency 키의 저장 위치(시도의 자식인지 컨텍스트 공용 테이블인지). 영속화 PR이 정한다.
- 토스페이먼츠 어댑터, WireMock·Toxiproxy 결함 주입, `FakePaymentGateway`의 시나리오 설정. 부르는 쪽이 생길 때 그 PR이 정한다.
- HTTP endpoint와 REST Docs.
- 분할 결제, 부분 환불, `attemptNo` 채번.

## 관련 문서
DESIGN 계층은 없다. 정본은 [도메인 모델](../wiki/domain-model.md) §2~§4다. 원문 근거는 [도메인 청사진 §1.1·§3](../references/05_Domain_Development_Blueprint.md), [외부 연동 전략 §3](../references/04_External_Integration_Strategy.md), [PRD](../references/01_ResilientPOS_PRD_Backend_Requirements.md)다. 같은 모양의 선행 PR은 [order-domain-model](2026-09-16-order-domain-model.md)이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/payment/**
- docs/**

## 인수 조건
1. 양수 금액으로 시도를 만들면 `REQUESTED`이고 `PaymentAttemptCreated` 하나가 난다.
2. 0원으로 시도를 만들면 `APPROVED`이고 `PaymentAttemptCreated`와 `PaymentApproved`가 순서대로 난다. provider를 부르지 않는다.
3. 음수 금액은 `NEGATIVE_PAYMENT_AMOUNT`로, 1 미만인 `checkoutNo`는 `INVALID_CHECKOUT_NO`로 거절한다. 둘 다 `DomainErrorKind.INVALID`라서 400이다.
4. `attemptNo`는 언제나 1이다.
5. `PaymentAttempt`는 `payment.internal.domain`에 있고 Spring·JPA·Jackson·adapter·application에 의존하지 않는다. `DependencyDirectionTest`의 기존 규칙이 자동으로 덮는다.
6. `FakePaymentGateway`가 `PaymentGateway`를 구현하고 같은 요청에 같은 결과를 돌려준다. 빈으로 등록되지 않는다.
7. `ModuleStructureTest`가 통과한다. 이 검사가 판정하는 것은 다른 모듈의 `internal` 참조와 **순환** 두 가지다.
8. `order`가 `payment`의 공개 이벤트를 소비해도 순환이 생기지 않는다.
9. 상태 이름 네 개와 그 순서가 테스트로 고정된다. DB에 문자열로 저장될 값이다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 모듈 경계는 `ModuleStructureTest`가, 도메인 레이어 의존성은 `DependencyDirectionTest`가 판정한다. 눈으로 보지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — `payment` 모듈이 더 이상 비어 있지 않다. 상태 어휘와 포트 경계를 적고, 모듈 개수 서술을 실제 값과 맞춘다.
- 요구사항 변화: 결제 상태 어휘 네 개와 0원 승인 규칙이 코드로 고정된다. 아직 외부로 나가는 endpoint가 없어 API 계약은 바뀌지 않는다.

## 분할 계획
예상은 12개 파일 / 약 450줄이었고 실제는 12개 파일 / 741줄이라 **600줄 경고에 걸렸다.** 내역은 main 코드 333줄, 테스트 234줄, 문서 174줄이다. 예상을 넘긴 것은 리뷰 반영으로 테스트 6개와 포트 갈래 하나가 늘고 그 근거 기록이 따라 늘었기 때문이다.

옮기지 않고 남긴다. 분리 가능한 조각은 `PaymentGateway` 포트와 `FakePaymentGateway`(157줄)뿐인데 이 PR의 범위로 정한 부분이고, 떼어 내면 같은 목표에 PR이 하나 더 쌓인다. 파일 수는 12개로 20개 한도의 절반이고 늘어난 줄의 절반 가까이가 문서다.

영속화와 리스너는 목표가 달라 처음부터 나눈다. [order-domain-model](2026-09-16-order-domain-model.md)이 같은 이유로 세 PR로 나뉘었다.

## TDD 검증 기록

RED는 새 타입이 없어 컴파일이 먼저 실패했다(`Unresolved reference 'PaymentAttempt'` 등 6건). 타입이 없는 상태에서 동작 실패를 따로 만들 방법이 없어, 컴파일 실패를 RED로 삼고 GREEN 뒤에 **되돌려서** 각 테스트가 실제로 무언가를 지키는지 확인했다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 양수 금액 시도는 REQUESTED이고 생성 사실 하나만 낸다 | `./gradlew compileTestKotlin` — 타입 없음 | `./gradlew test --tests '…payment.*'` — 통과 | `./gradlew test` — 통과 |
| 0원 시도는 provider를 부르지 않고 APPROVED이며 승인 사실까지 낸다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 0원 승인에는 provider 참조가 없다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 음수 금액을 거절한다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 1 미만인 checkout 회차를 거절한다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 초기 정책에서 시도 회차는 언제나 1이다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 같은 merchantReference는 언제나 같은 provider 참조를 받는다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 도메인 레이어 의존성 가드가 payment도 덮는다 | `PaymentAttempt`에 `@Component`를 붙이면 `DependencyDirectionTest` FAILED | 되돌린 뒤 통과 | `./gradlew test` — 통과 |

## 결과
`./gradlew test` 145개 통과다(직전 136 + 신규 9).

**인수 조건 5는 실제로 확인했다.** `DependencyDirectionTest`의 규칙이 `..internal.domain..` 와일드카드라 `payment`도 자동으로 덮일 것이라고 적었는데, 적은 것만으로는 덮인다고 말할 수 없다. `PaymentAttempt`에 `@Component`를 붙여 보니 그 어노테이션이 붙어 있다는 이유로 실패했다. 새 규칙을 더할 필요가 없다는 판단이 맞았다.

**0원 승인의 `providerReference`를 `null`로 뒀다.** 값을 지어내면 그 참조가 거짓이 되고, 나중에 provider 조회로 수렴할 때 존재하지 않는 참조를 찾게 된다. `null`이 "provider가 모르는 승인"이라는 사실을 그대로 나타낸다. 이 점을 테스트로 고정했다.

**0원이어도 `PaymentAttemptCreated`를 먼저 낸다.** 승인만 내면 0원 주문의 시도가 조회에 보이지 않아, "주문·결제·영수증 조회가 같은 경로로 보이게 한다"는 원래 목적이 깨진다.

**`FakePaymentGateway`의 `providerReference`를 요청에서 유도했다.** 무작위 값을 쓰면 같은 요청을 다시 보냈을 때 참조가 달라져서 재전송과 중복 승인을 구별하지 못한다. 결함 주입 시나리오는 넣지 않았다. 그 경로를 실제로 타는 호출부가 없는 상태에서 만들면 무엇이 옳은 동작인지 검증할 수단이 없다.

**포트를 `authorize` 하나로 좁혔다.** 원문은 `cancel`과 `findByMerchantReference`까지 그렸지만, 부르는 쪽 없이 인터페이스를 넓히면 그 모양이 맞는지 확인할 방법이 없다. 각 전이를 만드는 PR이 필요한 메서드를 그때 더한다.

**아직 부르는 쪽이 없다는 점은 이 PR의 알려진 한계다.** `FakePaymentGateway`는 `@Component`로 등록되지만 주입받는 곳이 없고, `PaymentManagement`도 구현이 없다. 영속화·Facade·`OrderPaymentRequested` 리스너가 다음 PR에서 이 셋을 잇는다.

**오류 code 이름을 고쳤다.** 처음에는 `INVALID_ATTEMPT_NO`라고 붙였는데 실제로 거절하는 값은 `checkoutNo`다. `attemptNo`는 명령이 주는 값이 아니라 aggregate가 정하므로 잘못될 수가 없다. code는 응답 본문의 `error.errorCode`로 나가는 계약이라, 이름이 틀리면 단말이 엉뚱한 필드를 고친다. `INVALID_CHECKOUT_NO`로 바꿨다.

## multi-review 반영

Claude 리뷰(74점)가 머지 전 해소 권장 3건과 should 4건을 냈다. `INVALID_CHECKOUT_NO` 개명은 리뷰가 오기 전에 같은 결함을 찾아 이미 고쳐 둔 상태였다.

**받았다 — `order ↔ payment` 순환.** 가장 무거운 지적이고 실제로 재현했다. `order/internal/application`에 `PaymentApproved`를 받는 클래스를 임시로 넣으니 `ModuleStructureTest`가 `Cycle detected: Slice order -> Slice payment -> Slice order`로 실패했다. 다음 PR이 바로 그 리스너를 만들므로 거기서 막힐 참이었다. `payment`의 공개 타입에서 `OrderId`를 걷어내고 raw `UUID`로 바꿨다. 같은 프로브로 다시 확인하니 통과한다.

**받았다 — `PaymentManagement`가 구현 없는 인터페이스다.** §4.4가 Facade를 구체 클래스로 두라고 정했고, 같은 PR의 `PaymentGateway` KDoc이 "부르는 쪽 없이 인터페이스를 먼저 넓히지 않는다"고 적어 놓고 `PaymentManagement`에는 그 원칙을 적용하지 않았다. 파일을 지우고 영속화 PR로 넘겼다.

**받았다 — 테스트가 통과하면서 깨지는 변경이 있다.** 지적한 두 가지를 실제로 변조해 재현했다. `eventId`를 둘 다 `paymentId.value`로 바꿔도, `checkoutNo`를 상수로 바꿔도 전부 통과했다. 무작위 생성기로는 ID 소비 순서를 단언할 수 없어 결정적 생성기로 바꾸고, `eventId` 유일성·`checkoutNo` 전달·`storeId`/`terminalId` 전달·`DomainErrorKind`·상태 이름 집합을 고정했다. 같은 변조로 이제 빨개지는 것을 확인했다.

**받았다 — `FakePaymentGateway`가 조건 없이 `@Component`다.** 설정이 빠진 배포에서 이것이 유일한 구현으로 남으면 모든 결제를 무조건 승인한다. 외부 연동 전략이 금지한 "Fake success"가 그 경로다. 지금 주입받는 곳이 없으므로 조건을 붙이는 대신 **빈 등록 자체를 뺐다.** 주입이 필요한 PR이 조건과 함께 등록한다.

**받았다 — `GatewayAuthorization`에 "호출하지 않음" 갈래가 없다.** circuit open·bulkhead 거절·rate limit을 `Unknown`에 담으면 일어나지 않은 결제에 reconciliation case와 미확인 알람이 쌓인다. `NotAttempted`를 더했다. 로컬 결함은 결과가 아니라 예외로 던진다는 것과, 호출부가 `approvedAmount`를 요청 금액과 대조해야 한다는 것도 포트 KDoc에 적었다.

**받았다 — `AuthorizePayment`에 공백 검증이 없다.** `merchantReference`가 비면 서로 다른 시도가 같은 provider 참조를 받아 재전송과 중복 승인을 구별할 수 없다. `require`를 넣고 테스트로 고정했다.

**받지 않았다 — `allowedDependencies`를 선언해 의존 방향을 잠그자는 제안.** 시험해 보니 **이 구성에서는 위반을 잡지 못한다.** `@ApplicationModule(allowedDependencies = {"foundation", "store", "terminal"})`를 붙이고 `payment`가 `order.OrderId`에 의존하게 만들어도 `ModuleStructureTest`가 통과했다. 어노테이션이 컴파일 결과에 제대로 들어간 것까지 `javap`으로 확인했고, 최상위 함수와 일반 클래스 프로퍼티 두 형태로 각각 시험했다. 거짓 안심을 주는 선언이라 넣지 않았다. 대신 인수 조건 7을 `ModuleStructureTest`가 실제로 판정하는 것(다른 모듈의 `internal` 참조와 순환)으로 줄이고, 순환이 없다는 사실은 인수 조건 8의 프로브로 확인했다.

**다음 PR로 넘겼다 — `attemptNo ≡ 1`과 멱등 키의 충돌.** 리뷰가 짚은 대로 `OrderPaymentRequested`의 KDoc은 `(orderId, checkoutNo)`를 멱등 근거로 선언하는데 `payment`는 `(orderId, attemptNo)`를 쓰고 거기서 `checkoutNo`가 사라진다. `checkoutNo`가 2 이상인 요청이 오면 unique 위반으로 500이 되거나(INSERT 경로), 선조회 멱등 판정에서 조용히 건너뛰어 주문이 영원히 미결제로 남는다(조회 경로). 뒤쪽이 더 위험하다. 다만 지금 `Order.requestCheckout`은 `OPEN`에서 한 번만 전이하므로 `checkoutNo`가 언제나 1이고, 이 결함은 취소·재시도가 생겨야 도달한다. 임시 제약을 응답 계약(`error.errorCode`)에 새기는 대신 `FIRST_ATTEMPT_NO`의 주석과 알려진 빈틈에 두 실패 모드를 적었다. 멱등 키를 정하는 영속화 PR이 닫는다.

**다음 PR로 넘겼다 — `providerReference`가 null인 이유를 타입으로 남기자는 제안, `MerchantReference`/`ProviderIdempotencyKey` 값 타입, `provider` 스코프.** 셋 다 타당하지만 provider 결과를 적용하는 전이가 없는 지금은 그 모양이 맞는지 확인할 수단이 없다.

## 결과 (2차)
`./gradlew test` 152개 통과다(직전 136 + 신규 16).
