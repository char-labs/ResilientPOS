# PR spec: payment의 PaymentAttempt aggregate

## 목적
두 번째 업무 도메인을 `payment` 모듈에 만든다. `PaymentAttempt` aggregate가 결제 시도 생성 불변식을 소유하고, `CreatePaymentAttempt` 명령의 공개 계약과 결제 상태 어휘를 고정한다. 한 주문에 시도가 여러 건 달리는 관계를 코드로 고정하고, 테이블은 뒤따르는 PR이 만든다.

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

**한 주문에 시도가 여럿 달린다.** 용어집이 "승인과 취소는 각각 별도 attempt"라고 정했고 외부 연동 전략도 "cancel은 별도 local attempt를 기록한다"고 적었다. 거절 뒤 재시도도 새 시도다.

**복합 자연 키를 두지 않는다.** 식별은 `PaymentId` 하나가 하고 `orderId`는 참조일 뿐이다. `(orderId, attemptNo)` 같은 키는 한 주문의 시도 수를 제한하는 쪽으로 작동해서 1-to-many를 막는다. 회차 번호(`attemptNo`)도 두지 않는다. 순서가 필요하면 생성 시각으로 정렬한다. 용어집이 정한 `(orderId, attemptNo)`를 이 PR이 바꾼다.

같은 이벤트가 두 번 전달돼 시도가 두 번 만들어지는 것은 이 키가 막을 일이 아니었다. Event Publication Registry가 `(이벤트, 리스너)`마다 완료를 기록하므로 성공한 리스너는 다시 불리지 않는다. 재발행을 켜면 at-least-once가 되고, 그때는 업무 수준 멱등 근거가 따로 필요하다.

**무엇을 요청한 시도인지 `PaymentAttemptKind`가 말한다.** 구분자가 없으면 저장된 행이 승인인지 취소인지 알 수 없고, `PaymentApproved`를 받는 `order`가 환불 승인을 결제 승인으로 읽어 주문을 결제 완료로 넘긴다. 상태는 그대로 둔다. 두 축이 나뉘어 있어서 외부 연동 전략의 `CANCEL_PENDING`은 (`CANCELLATION`, `REQUESTED`)로 표현된다.

계약: 새 `DomainException` code 두 개가 나중에 응답 본문의 `error.errorCode`로 나간다.

| code | 의미 |
|---|---|
| `NEGATIVE_PAYMENT_AMOUNT` | 결제 금액이 음수다 |
| `INVALID_CHECKOUT_NO` | checkout 회차가 1 미만이다 |

복구: 둘 다 요청 자체가 잘못된 경우라 재시도로 풀리지 않는다.

관측성: 이 PR은 저장·외부 호출·트랜잭션이 없어 로그 속성과 재시도 경로가 생기지 않는다.

## 제외
- JPA 엔티티와 `t_payment_attempt` 마이그레이션. **바로 뒤따르는 PR**이 만든다. 이 PR은 도메인 한 목표만 담는다.
- Facade와 `PaymentAttemptRepository` 포트·adapter. [도메인 모델](../wiki/domain-model.md) §4.4가 "Facade는 구현이 둘이 될 때 인터페이스를 뽑는다"고 정했고, 호출부가 없으면 시그니처가 맞는지 확인할 수단이 없다.
- `OrderPaymentRequested`를 받는 `@ApplicationModuleListener`와 그에 딸린 재시도·풀·종료 정책. 그 PR이 [현재 상태](../wiki/current-state.md)에 적힌 Event Publication Registry의 빈틈도 함께 닫는다.
- provider 결과를 적용하는 전이(`APPROVED`·`DECLINED`·`UNKNOWN`)와 그때 나는 `PaymentDeclined`·`PaymentUnknown` 이벤트. 상태 값만 이 PR이 선언하고 전이는 결과를 받는 PR이 만든다.
- `PaymentGateway` 포트와 `FakePaymentGateway`. **부르는 쪽과 같은 PR에 둔다.** 포트를 호출부 없이 먼저 만들면 그 모양이 맞는지 확인할 수단이 없다. Facade와 리스너를 만드는 다음 PR이 함께 가져간다.
- provider idempotency 키의 저장 위치(시도의 자식인지 컨텍스트 공용 테이블인지). 영속화 PR이 정한다.
- 토스페이먼츠 어댑터와 WireMock·Toxiproxy 결함 주입.
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
4. 식별에 `orderId`가 참여하지 않는다. 한 주문에 시도를 몇 건 만들어도 서로 다른 값이 된다.
5. `PaymentAttempt`는 `payment.internal.domain`에 있고 Spring·JPA·Jackson·adapter·application에 의존하지 않는다. `DependencyDirectionTest`의 기존 규칙이 자동으로 덮는다.
6. `ModuleStructureTest`가 통과한다. 이 검사가 판정하는 것은 다른 모듈의 `internal` 참조와 **순환** 두 가지다.
7. `order`가 `payment`의 공개 이벤트를 소비해도 순환이 생기지 않는다.
8. 상태 이름과 시도 종류 이름 네 개와 그 순서가 테스트로 고정된다. DB에 문자열로 저장될 값이다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 모듈 경계는 `ModuleStructureTest`가, 도메인 레이어 의존성은 `DependencyDirectionTest`가 판정한다. 눈으로 보지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — `payment` 모듈이 더 이상 비어 있지 않다. 상태 어휘와 포트 경계를 적고, 모듈 개수 서술을 실제 값과 맞춘다.
- 요구사항 변화: 결제 상태 어휘 네 개와 0원 승인 규칙이 코드로 고정된다. 아직 외부로 나가는 endpoint가 없어 API 계약은 바뀌지 않는다.

## 분할 계획
8개 파일 / 약 600줄이다. 경고 한도에 걸렸고 사용자가 허용했다.

한 번은 JPA 엔티티와 테이블까지 담았다가 800줄 상한을 넘겼다. 주석 비중이 17%로 저장소 평균과 같아 줄여서 맞출 수 있는 종류가 아니었고, 목표가 둘(도메인과 영속화)이라는 신호로 읽고 [payment-attempt-persistence](2026-09-22-payment-attempt-persistence.md)로 나눴다.

`PaymentGateway` 포트도 이 PR에 두지 않는다. 호출부 없이 인터페이스를 먼저 만들면 모양이 맞는지 확인할 수단이 없어서, 실제로 주입하는 PR로 옮겼다.

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
`./gradlew test` 149개 통과다(직전 136 + 신규 13).

확인한 사실만 적는다.

- **복합 자연 키를 걷어낸 것이 이 PR의 핵심 변경이다.** `attemptNo`와 `(orderId, attemptNo)`를 유지하면 회차를 1로 고정하지 않더라도 한 주문의 시도 수를 제한하는 쪽으로 작동한다. 식별은 `PaymentId` 하나가 하고 `orderId`는 참조일 뿐이다.
- **순환 의존.** `order`에 `PaymentApproved`를 받는 클래스를 임시로 두면 `Cycle detected: Slice order -> Slice payment -> Slice order`로 `ModuleStructureTest`가 실패한다. `orderId`를 raw `UUID`로 든 뒤 같은 프로브로 통과를 확인했다.
- **`allowedDependencies`는 쓸 수 없다.** 선언하고 허용하지 않은 모듈에 의존하게 만들어도 검사가 통과한다. 어노테이션이 컴파일 결과에 들어간 것은 `javap`으로 확인했다. 의존 방향을 잠그는 수단이 아니라서 넣지 않았다.
- **의존성 가드는 `payment`도 덮는다.** `PaymentAttempt`에 `@Component`를 붙이면 `DependencyDirectionTest`가 실패한다.
- **테스트가 통과하면서 깨지던 변경 넷을 막았다.** `eventId`를 두 이벤트 모두 같은 값으로, `checkoutNo`를 상수로, `DECLINED`를 `REJECTED`로 바꿔도, 서로 다른 시도가 같은 `PaymentId`를 받아도 전부 통과했다. `eventId`는 소비자 멱등성 기록의 키라 그 구멍의 결과가 승인 사실 유실이다.
- **0원 승인의 `providerReference`는 `null`이다.** 값을 지어내면 provider 조회로 수렴할 때 없는 참조를 찾는다. 0원이어도 `PaymentAttemptCreated`를 먼저 내야 시도가 조회에 보인다.

검토: multi-review를 실행했다(Claude 74점, 블로킹 없음). 순환 의존, Facade 인터페이스, 테스트 구멍을 받았고 `allowedDependencies` 제안은 위와 같이 반증했다.
