# PR spec: Order의 RequestCheckout 전이와 OrderPaymentRequested

## 목적
주문은 지금 `OPEN` 상태 하나뿐이라 결제로 넘어갈 수 없다. `Order`가 결제를 요청하는 시점의 금액을 확정하고 `CHECKOUT_REQUESTED`로 전이하면서 `OrderPaymentRequested`를 만드는 도메인 규칙을 추가한다. `RequestCheckout`을 세 PR로 나눈 첫 번째이고, 이 PR은 Spring·DB 없이 값 비교로 검증할 수 있는 도메인만 담는다.

## 범위
- 소유 모듈: `order`. 전이 규칙은 `internal/domain`의 `Order`가 소유한다.
- 상태: `OrderStatus`에 `CHECKOUT_REQUESTED`를 더한다.
- 값 객체 `Checkout`(`internal/domain`): 결제 시도 회차 `checkoutNo`와 확정 금액 `amount`(`Money`, 통화 포함)를 가진다. `Order`는 회차별 `checkouts` 목록을 가진다. 용어와 자식 구성은 [도메인 모델](../wiki/domain-model.md) 2절·3절을 따른다.
- 전이 `order.requestCheckout(ids, now)`: [도메인 모델](../wiki/domain-model.md) 4.1절대로 자신을 바꾸지 않고 `Transition`을 돌려준다.
  - `OPEN`이면 `CHECKOUT_REQUESTED` 상태이고 `checkouts`에 `Checkout(기존 회차 수 + 1, total)`이 더해진 새 `Order`와 `OrderPaymentRequested` 한 건을 돌려준다.
  - 이미 `CHECKOUT_REQUESTED`이면 같은 `Order`와 빈 이벤트 목록을 돌려준다. 응답을 잃은 단말의 재시도가 기존 결과를 받게 하려는 것이고, 의미 있는 이벤트를 두 번 내지 않는다.
  - 분기는 else 없는 `when`이다. 상태가 늘면 이 전이에서의 처리를 정하지 않고는 컴파일되지 않는다.
- 이벤트 `OrderPaymentRequested`(모듈 루트, `OrderEvent`): `eventId`, `occurredAt`, `orderId`, `storeId`, `terminalId`, `checkoutNo`, `amount`. `payment`가 attempt를 만들 때 필요한 값이고 `(orderId, checkoutNo)`가 그쪽 멱등 판정의 근거가 된다.
- 0원 주문: 합계가 0이어도 똑같이 전이하고 0원 `Checkout`과 이벤트를 남긴다. 전액 할인·프로모션 주문이 계산대에서 거절되지 않아야 하고, 주문·결제·영수증 조회가 같은 경로로 보여야 한다. PG 호출을 건너뛰는 판단은 `payment`가 맡는다. `payment` PR은 0원 attempt를 provider 호출 없이 승인으로 기록하고 `PaymentApproved`를 내야 한다.
- 정합성 불변식: checkout 이후 금액·통화는 바뀌지 않는다. 지금은 line을 바꾸는 명령이 없어서 `Checkout.amount`와 `total`이 어긋날 경로가 없다. `ChangeOrderLine`을 넣는 PR이 `OPEN`에서만 허용하도록 막는다.
- `Order.restore`에 `checkouts` 인자를 더한다. 기존 통합 테스트의 helper `withLines`가 `restore`를 직접 부르므로 인자만 맞춘다(범위 조정, 2026-09-21: 컴파일 오류로 발견). `OrderEntity.toDomain()`은 다음 PR이 `t_order_checkout`을 읽을 때까지 빈 목록을 넘긴다. 이 PR에는 `CHECKOUT_REQUESTED` 주문을 저장하는 경로(Facade)가 없어서 빈 목록이 틀린 값이 되는 경우가 없다.
- 실패·재시도·복구, 관측성: 이 PR은 저장·외부 호출·트랜잭션이 없어 해당 없다. 재시도 판정의 도메인 쪽 절반(위 두 번째 분기)만 여기에 있다.

### 합의한 결정 (2026-09-21 grill)
- store 정책, `CheckoutPolicy`, 정책 버전은 만들지 않는다. 합계는 단가 곱 수량의 합이고 `Money`가 통화 자릿수를 강제해서 반올림할 계산이 없다. [도메인 모델](../wiki/domain-model.md) 4.7절 질문 2·3에 걸린다(읽는 코드가 없고 나중에 채울 수 있다). 할인·세금처럼 반올림이 필요한 첫 PR이 store 정책과 정책 버전을 함께 도입한다. 그래서 전이 시그니처는 wiki 4.1절의 `requestCheckout(policy)`가 아니라 `requestCheckout(ids, now)`다.
- 재요청은 상태로 멱등을 판정한다. 요청 키는 두지 않는다. 결제 실패 뒤 재-checkout(2회차)이 생기면 늦게 도착한 1회차 재시도가 2회차를 만들 수 있으므로, 그 PR이 요청 키나 `expectedVersion`을 다시 본다.
- 0원 주문은 거절하지 않는다(위 범위).
- PR은 도메인, 영속화·Facade, HTTP API 세 개로 나눈다. 한 PR이면 약 900줄로 실패 한도(800줄)를 넘는다.

## 제외
- `RequestCheckoutCommand`·`RequestCheckoutResult`, Facade 메서드, `t_order_checkout` 마이그레이션과 엔티티, 트랜잭션. 다음 PR `order-request-checkout-persistence`.
- `POST` endpoint와 REST Docs. 그다음 PR `order-request-checkout-api`.
- Event Publication Registry. `OrderPaymentRequested`를 받는 consumer가 생기는 `payment` PR 전에 도입한다.
- `OPEN`·`CHECKOUT_REQUESTED` 밖의 상태에서의 거절(`INVALID_ORDER_STATE`). 그런 상태가 아직 없어서 도달할 수 없는 분기다. 상태를 더하는 PR이 else 없는 `when` 때문에 함께 정한다.
- 요청한 단말이 주문의 단말·매장과 같은지 확인하는 인가. 단말 인증이 아직 없다.
- `CheckoutRequested` 이벤트. 청사진 3.1절은 두 이벤트를 적지만 [도메인 모델](../wiki/domain-model.md) 4.1절이 `OrderPaymentRequested` 하나로 정했다.

## 관련 문서
없음.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/OrderStatus.kt
- src/main/kotlin/com/resilientpos/order/OrderEvent.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Checkout.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderEntity.kt
- src/test/kotlin/com/resilientpos/order/internal/domain/OrderTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/OrderCommandFacadeIntegrationTest.kt
- docs/spec/2026-09-21-order-request-checkout-domain.md
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/changes.md

## 인수 조건
1. `OPEN` 주문에 `requestCheckout`을 부르면 돌려받은 `Order`는 `CHECKOUT_REQUESTED`이고 `checkouts`가 `Checkout(1, total)` 하나다. `id`·`storeId`·`terminalId`·`clientOrderId`·`currency`·`lines`는 그대로다. 원본 `Order`는 여전히 `OPEN`이고 `checkouts`가 비어 있다.
2. 같은 호출이 `OrderPaymentRequested` 정확히 한 건을 돌려준다. `eventId`는 식별자 생성기의 값, `occurredAt`은 넘긴 시각이고 `orderId`·`storeId`·`terminalId`는 주문의 값, `checkoutNo`는 1, `amount`는 주문의 `total`이다.
3. 합계가 0인 주문도 1과 같이 전이하고 `Checkout`과 이벤트의 `amount`가 주문 통화의 0이다.
4. 이미 `CHECKOUT_REQUESTED`인 주문에 다시 부르면 돌려받은 `Order`의 상태와 `checkouts`가 그대로이고 이벤트 목록이 비어 있다.
5. 기존 테스트 59개와 모듈 구조·의존성 방향 테스트가 그대로 통과한다.

## 검증
- 도메인: `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`
- 전체 회귀: `./gradlew test` (Docker 필요)
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 인수 조건 1~3. 재시도 시나리오: 인수 조건 4. 이 PR에는 거절되는 입력이 없다(제외 참고).

## Wiki 영향
- [현재 상태](../wiki/current-state.md): `order` 모듈 설명에 `CHECKOUT_REQUESTED`, `Checkout`, `requestCheckout`, `OrderPaymentRequested`와 아직 저장 경로가 없다는 점, 테스트 수를 반영한다.
- [도메인 모델](../wiki/domain-model.md): 4.1절의 `order.requestCheckout(policy)` 예시를 실제 시그니처와 정책을 미룬 이유로 고치고, 용어집 `checkout`의 "정책 버전"이 아직 없다는 점을 적는다. 0원 주문을 `payment`가 provider 호출 없이 승인해야 한다는 조건을 컨텍스트 사이 흐름에 적는다.
- [변경 기록](../wiki/changes.md): 이 spec 링크, 합의한 결정, 요구사항 영향을 적는다.

## 분할 계획
예상 규모는 구현 약 60줄, 테스트 약 90줄, 문서 약 130줄로 총 280줄·10파일 내외다. 경고 한도(600줄·20파일) 안이다. 초과하면 wiki의 도메인 모델 갱신을 다음 PR로 옮긴다. 후속 순서는 `order-request-checkout-persistence`(`V003` `t_order_checkout`, 엔티티, Command·Result, Facade, 재시도 통합 테스트) → `order-request-checkout-api`(endpoint, REST Docs)다. 앞 PR이 머지된 뒤 main에서 다음 PR을 시작한다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 1: `OPEN 주문의 requestCheckout은 CHECKOUT_REQUESTED와 1회차 Checkout을 가진 새 주문을 돌려주고 원본은 그대로다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 2: `requestCheckout은 확정 금액을 담은 OrderPaymentRequested 한 건을 만든다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 3: `합계가 0인 주문도 0원 Checkout으로 전이한다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 4: `이미 CHECKOUT_REQUESTED인 주문의 requestCheckout은 같은 주문과 빈 이벤트를 돌려준다` | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
