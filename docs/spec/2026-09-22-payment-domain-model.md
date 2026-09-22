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

**한 주문에 시도가 여럿 달린다.** 용어집이 "승인과 취소는 각각 별도 attempt"라고 정했고 외부 연동 전략도 "cancel은 별도 local attempt를 기록한다"고 적었다. 거절 뒤 재시도도 새 시도다. `(orderId, attemptNo)`가 자연 키이고, 회차가 다르면 같은 주문이어도 다른 행이 된다.

`attemptNo`는 명령이 아니라 **저장된 시도 이력에서 정해지는 값**이라 `create`의 별도 인자로 받는다. 시도들은 서로 다른 aggregate라서 자기 안에서 회차를 셀 수 없다. 부르는 쪽이 그 주문의 마지막 회차 + 1을 넘긴다. 개수 + 1이 아니다 — 중간 회차가 지워지면 번호가 겹친다. 동시에 두 요청이 같은 번호를 계산하는 경합은 `(orderId, attemptNo)` unique가 마지막으로 막는다.

이벤트가 두 번 전달돼 시도가 두 번 만들어지는 것은 이 키가 막을 일이 아니다. Event Publication Registry가 `(이벤트, 리스너)`마다 완료를 기록하므로 성공한 리스너는 다시 불리지 않는다. 재발행을 켜면 at-least-once가 되므로 그때는 업무 수준 멱등 근거가 따로 필요하고, 그 PR이 정한다.

**`payment`는 `order`를 import하지 않는다.** 공개 타입이 `orderId`를 `order.OrderId`가 아니라 raw `UUID`로 든다. `OrderId`를 쓰면 `order`가 `PaymentApproved`를 소비하는 순간 `order -> payment -> order` 순환이 되어 Modulith 검증이 실패한다. 타입 안전성은 `payment` 경계 안에서만 잃고 `order`는 받은 값을 `OrderId`로 감싼다.

**`PaymentGateway` 포트를 정의한다.** 외부 연동 전략이 정한 경계다. provider SDK DTO·HTTP 상태·비밀값을 노출하지 않는다. 이 PR은 `authorize` 하나만 둔다. 결과는 승인·거절·미확인 세 갈래를 가진 sealed 타입이라 "timeout은 실패가 아니다"가 타입으로 드러난다.

원문의 포트는 `suspend` 함수인데 **블로킹으로 만든다.** 이 저장소에 코루틴 의존성이 없고 Spring MVC가 블로킹이다. 코루틴을 들이는 것은 이 PR의 목표가 아니다.

**`FakePaymentGateway`를 유일한 구현으로 둔다.** 외부 연동 전략이 Fake PG를 P0로 두고 "항상 유지"라고 적었다. 실제 provider가 붙어도 지우지 않는다.

계약: 새 `DomainException` code 세 개가 나중에 응답 본문의 `error.errorCode`로 나간다.

| code | 의미 |
|---|---|
| `NEGATIVE_PAYMENT_AMOUNT` | 결제 금액이 음수다 |
| `INVALID_CHECKOUT_NO` | checkout 회차가 1 미만이다 |
| `INVALID_ATTEMPT_NO` | 시도 회차가 1 미만이다. 부르는 쪽이 정하는 값이라 여기 걸리면 채번이 틀린 것이다 |

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
- 분할 결제와 부분 환불의 금액 배분(allocation). 청사진이 "초기 정책은 주문당 `PaymentAttempt` 한 건"이라고 한 것은 이 배분을 아직 하지 않는다는 뜻이고, 시도를 하나로 제한하라는 뜻이 아니다.
- `attemptNo` 채번 구현. 저장된 이력을 읽어야 하므로 repository가 생기는 PR이 맡는다. 이 PR은 받은 값을 검증만 한다.

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
4. 같은 주문의 서로 다른 `attemptNo`는 서로 다른 시도가 된다. 1 미만인 `attemptNo`는 `INVALID_ATTEMPT_NO`로 거절한다.
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
예상은 12개 파일 / 약 450줄이었고 실제는 12개 파일 / 778줄이라 **600줄 경고에 걸렸다.** 리뷰 반영으로 테스트가 9개 늘고 포트 갈래가 하나 늘었다. 한 번은 800줄 상한까지 넘겼는데, 원인이 spec에 쌓인 과정 서술이어서 최종 내용만 남기고 걷어냈다.

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
`./gradlew test` 155개 통과다(직전 136 + 신규 19).

**순환 의존을 미리 끊었다.** `order/internal/application`에 `PaymentApproved`를 받는 클래스를 임시로 넣으니 `ModuleStructureTest`가 `Cycle detected: Slice order -> Slice payment -> Slice order`로 실패한다. 다음 PR이 바로 그 리스너를 만든다. `payment`의 공개 타입에서 `OrderId`를 걷어내고 raw `UUID`로 바꾼 뒤 같은 프로브로 통과를 확인했다.

**`@ApplicationModule(allowedDependencies = ...)`는 이 구성에서 위반을 잡지 못한다.** `payment`에 선언하고 허용하지 않은 모듈에 의존하게 만들어도 `ModuleStructureTest`가 통과한다. 어노테이션이 컴파일 결과에 들어간 것은 `javap`으로 확인했으므로 선언 누락이 아니다. 최상위 함수와 클래스 프로퍼티 두 형태로 각각 시험했다. 의존 방향을 잠그는 수단으로 쓸 수 없어 넣지 않았고, 인수 조건 7을 `ModuleStructureTest`가 실제로 판정하는 것으로 줄였다.

**의존성 가드가 `payment`도 덮는다.** `DependencyDirectionTest`의 `..internal.domain..` 규칙이 자동으로 덮일 것이라고 적은 뒤 `PaymentAttempt`에 `@Component`를 붙여 실제로 실패하는 것을 확인했다. 새 규칙이 필요 없다.

**테스트가 통과하면서 깨지던 변경 셋을 막았다.** `eventId`를 두 이벤트 모두 `paymentId.value`로 바꿔도, `checkoutNo`를 상수로 바꿔도, `DECLINED`를 `REJECTED`로 바꿔도 전부 통과했다. `eventId`는 소비자 멱등성 기록 `(consumer, eventId)`의 키라 그 구멍의 결과가 승인 사실 유실이다. 결정적 생성기로 바꾸고 `eventId` 유일성·`checkoutNo` 전달·`storeId`/`terminalId` 전달·`DomainErrorKind`·상태 이름 집합을 고정한 뒤, 같은 변조로 빨개지는 것을 확인했다.

**0원 승인의 `providerReference`는 `null`이다.** 값을 지어내면 그 참조가 거짓이 되고 나중에 provider 조회로 수렴할 때 없는 참조를 찾게 된다. `null`이 "provider가 모르는 승인"이라는 사실을 그대로 나타낸다. 0원이어도 `PaymentAttemptCreated`를 먼저 내는 이유는, 승인만 내면 0원 주문의 시도가 조회에 보이지 않아 "주문·결제·영수증 조회가 같은 경로로 보인다"는 목적이 깨지기 때문이다.

**`FakePaymentGateway`는 빈으로 등록하지 않는다.** 설정이 빠진 배포에서 이것이 유일한 구현으로 남으면 모든 결제를 무조건 승인한다. 외부 연동 전략이 금지한 "Fake success"가 그 경로다. `providerReference`는 `merchantReference`에서 유도한다. 무작위 값이면 같은 요청을 다시 보냈을 때 참조가 달라져 재전송과 중복 승인을 구별하지 못한다.

**Facade는 만들지 않았다.** [도메인 모델](../wiki/domain-model.md) §4.4가 Facade를 구체 클래스로 두고 구현이 둘일 때 인터페이스를 뽑으라고 정했다. 구현도 호출부도 없는 상태에서는 시그니처가 맞는지 확인할 수단이 없다.

검토: 제출 전 multi-review를 실행했다(Claude 74점, 블로킹 없음). 순환 의존, Facade 인터페이스, 테스트 구멍 셋, Fake 빈 등록, 포트의 "호출하지 않음" 갈래 누락, `AuthorizePayment` 공백 검증을 받아 고쳤다. `allowedDependencies` 제안은 위와 같이 실측으로 반증해 받지 않았다. `MerchantReference`·`ProviderIdempotencyKey` 값 타입과 `provider` 스코프, `providerReference` 불변식의 타입화는 provider 결과를 적용하는 전이가 없는 지금 모양을 확인할 수단이 없어 다음 PR로 넘겼다.
