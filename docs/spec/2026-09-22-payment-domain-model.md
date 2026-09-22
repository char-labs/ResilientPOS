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

네 값을 모두 선언한다. 이 PR이 만드는 전이는 `REQUESTED`와 `APPROVED`뿐이지만, 상태 집합 자체가 이 PR이 고정하는 계약이고 `when`이 exhaustive라서 전이를 더하는 PR이 매핑을 빠뜨리면 컴파일되지 않는다.

**생성 불변식을 aggregate가 소유한다.** 금액은 음수일 수 없고 `checkoutNo`와 `attemptNo`는 1 이상이다. 금액의 자릿수·범위 검증은 `Money`가 이미 한다.

**0원은 provider를 부르지 않고 승인으로 기록한다.** [도메인 모델](../wiki/domain-model.md) §3이 정한 규칙이다. `order`가 0원 주문도 똑같이 checkout을 확정하고 `OrderPaymentRequested`를 내므로, `payment`가 여기서 거절하면 전액 할인·프로모션 주문이 결제 단계에서 막힌다. 생성 결과가 `APPROVED`이고 이벤트를 두 개(`PaymentAttemptCreated`, `PaymentApproved`) 낸다. 시도를 기록으로 남겨야 주문·결제·영수증 조회가 0원 주문에서도 같은 경로로 보인다.

**`attemptNo`는 1로 고정한다.** 청사진이 "초기 정책은 주문당 `PaymentAttempt` 한 건"이라고 정했다. `(orderId, attemptNo)` unique 제약이 그 정책을 강제한다. 회차를 세는 일은 저장된 시도를 읽어야 가능한데 이 PR에는 repository가 없다. 취소를 별도 시도로 기록하거나 분할 결제를 다루는 PR이 채번 규칙을 정한다.

**`PaymentGateway` 포트를 정의한다.** 외부 연동 전략이 정한 경계다. provider SDK DTO·HTTP 상태·비밀값을 노출하지 않는다. 이 PR은 `authorize` 하나만 둔다. 결과는 승인·거절·미확인 세 갈래를 가진 sealed 타입이라 "timeout은 실패가 아니다"가 타입으로 드러난다.

원문의 포트는 `suspend` 함수인데 **블로킹으로 만든다.** 이 저장소에 코루틴 의존성이 없고 Spring MVC가 블로킹이다. 코루틴을 들이는 것은 이 PR의 목표가 아니다.

**`FakePaymentGateway`를 유일한 구현으로 둔다.** 외부 연동 전략이 Fake PG를 P0로 두고 "항상 유지"라고 적었다. 실제 provider가 붙어도 지우지 않는다.

계약: 새 `DomainException` code 두 개가 나중에 응답 본문의 `error.errorCode`로 나간다.

| code | 의미 |
|---|---|
| `NEGATIVE_PAYMENT_AMOUNT` | 결제 금액이 음수다 |
| `INVALID_ATTEMPT_NO` | 시도 회차가 1 미만이다 |

복구: 둘 다 요청 자체가 잘못된 경우라 재시도로 풀리지 않는다.

관측성: 이 PR은 저장·외부 호출·트랜잭션이 없어 로그 속성과 재시도 경로가 생기지 않는다. `FakePaymentGateway`도 부르는 쪽이 없다.

## 제외
- `PaymentManagement` Facade 구현, `PaymentAttemptRepository` 포트와 JPA adapter, `t_payment_attempt` 마이그레이션. 다음 PR.
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
3. 음수 금액은 `NEGATIVE_PAYMENT_AMOUNT`로, 1 미만인 `checkoutNo`·`attemptNo`는 `INVALID_ATTEMPT_NO`로 거절한다.
4. `attemptNo`는 언제나 1이다.
5. `PaymentAttempt`는 `payment.internal.domain`에 있고 Spring·JPA·Jackson·adapter·application에 의존하지 않는다. `DependencyDirectionTest`의 기존 규칙이 자동으로 덮는다.
6. `FakePaymentGateway`가 `PaymentGateway`를 구현하고 같은 요청에 같은 결과를 돌려준다.
7. `ModuleStructureTest`가 통과한다. `payment`는 `order`·`store`·`terminal`의 ID 값 타입과 `foundation`에만 의존한다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 모듈 경계는 `ModuleStructureTest`가, 도메인 레이어 의존성은 `DependencyDirectionTest`가 판정한다. 눈으로 보지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — `payment` 모듈이 더 이상 비어 있지 않다. 상태 어휘와 포트 경계를 적고, 모듈 개수 서술을 실제 값과 맞춘다.
- 요구사항 변화: 결제 상태 어휘 네 개와 0원 승인 규칙이 코드로 고정된다. 아직 외부로 나가는 endpoint가 없어 API 계약은 바뀌지 않는다.

## 분할 계획
예상 12개 파일 안팎 / 약 450줄. 경고 한도(600줄·20파일) 안이다. 영속화와 리스너는 목표가 달라 처음부터 나눈다. [order-domain-model](2026-09-16-order-domain-model.md)이 같은 이유로 세 PR로 나뉘었다.

## TDD 검증 기록

RED는 새 타입이 없어 컴파일이 먼저 실패했다(`Unresolved reference 'PaymentAttempt'` 등 6건). 타입이 없는 상태에서 동작 실패를 따로 만들 방법이 없어, 컴파일 실패를 RED로 삼고 GREEN 뒤에 **되돌려서** 각 테스트가 실제로 무언가를 지키는지 확인했다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 양수 금액 시도는 REQUESTED이고 생성 사실 하나만 낸다 | `./gradlew compileTestKotlin` — 타입 없음 | `./gradlew test --tests '…payment.*'` — 통과 | `./gradlew test` — 통과 |
| 0원 시도는 provider를 부르지 않고 APPROVED이며 승인 사실까지 낸다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 0원 승인에는 provider 참조가 없다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 음수 금액을 거절한다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 1 미만인 회차를 거절한다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
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
