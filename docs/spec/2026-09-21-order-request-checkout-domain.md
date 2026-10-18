# PR spec: Order의 RequestCheckout 전이와 OrderPaymentRequested

## 목적
주문은 지금 `OPEN` 상태 하나뿐이라 결제로 넘어갈 수 없다. `Order`가 결제를 요청하는 시점의 금액을 확정하고 `CHECKOUT_REQUESTED`로 전이하면서 `OrderPaymentRequested`를 만드는 도메인 규칙을 추가한다. `RequestCheckout`을 세 PR로 나눈 첫 번째이고, 이 PR은 Spring·DB 없이 값 비교로 검증할 수 있는 도메인만 담는다.

## 범위
- 소유 모듈: `order`. 전이 규칙은 `internal/domain`의 `Order`가 소유한다.
- 상태: `OrderStatus`에 `CHECKOUT_REQUESTED`를 더한다. `t_order.status`는 `varchar(32)`이고 이 이름은 18자라 마이그레이션이 필요 없다. 이 값을 쓰는 코드(다음 PR)보다 enum 상수가 먼저 배포되므로 이전 버전 인스턴스가 모르는 문자열을 읽는 구간이 없다. 이 값은 응답 본문으로도 나간다. `OrderCommandFacade.create`의 재시도 경로가 저장된 주문의 `status`를 `POST /v1/orders` 응답에 그대로 싣기 때문이다. 이 PR에는 `CHECKOUT_REQUESTED` 주문이 저장되는 경로가 없어서 응답에 나타나지 않는다(범위 조정, 2026-09-21: PR 제출 뒤 Risk 점검에서 발견).
- 값 객체 `Checkout`(`internal/domain`): 결제 시도 회차 `checkoutNo`와 확정 금액 `amount`(`Money`, 통화 포함)를 가진다. `Order`는 회차별 `checkouts` 목록을 가진다. 용어와 자식 구성은 [도메인 모델](../wiki/domain-model.md) 2절·3절을 따른다.
- 전이 `order.requestCheckout(ids, now)`: [도메인 모델](../wiki/domain-model.md) 4.1절대로 자신을 바꾸지 않고 `Transition`을 돌려준다.
  - `OPEN`이면 `CHECKOUT_REQUESTED` 상태이고 `checkouts`에 `Checkout(기존 회차 수 + 1, total)`이 더해진 새 `Order`와 `OrderPaymentRequested` 한 건을 돌려준다.
  - 이미 `CHECKOUT_REQUESTED`이면 같은 `Order`와 빈 이벤트 목록을 돌려준다. 응답을 잃은 단말의 재시도가 기존 결과를 받게 하려는 것이고, 의미 있는 이벤트를 두 번 내지 않는다.
  - 분기는 else 없는 `when`이다. 상태가 늘면 이 전이에서의 처리를 정하지 않고는 컴파일되지 않는다.
- 이벤트 `OrderPaymentRequested`(모듈 루트, `OrderEvent`): `eventId`, `occurredAt`, `orderId`, `storeId`, `terminalId`, `checkoutNo`, `amount`. `payment`가 attempt를 만들 때 필요한 값이다. `(orderId, checkoutNo)`는 같은 checkout에 대한 승인 attempt를 두 번 만들지 않는 근거다. `attemptNo`와의 대응(취소는 별도 attempt다)과 `(consumer, eventId)` 재전달 방어와의 역할 분담은 `payment` PR이 정한다.
- 0원 주문: 합계가 0이어도 똑같이 전이하고 0원 `Checkout`과 이벤트를 남긴다. 전액 할인·프로모션 주문이 계산대에서 거절되지 않아야 하고, 주문·결제·영수증 조회가 같은 경로로 보여야 한다. PG 호출을 건너뛰는 판단은 `payment`가 맡는다. `payment` PR은 0원 attempt를 provider 호출 없이 승인으로 기록하고 `PaymentApproved`를 내야 한다.
- 정합성 불변식: checkout 이후 금액·통화는 바뀌지 않는다. 지금은 line을 바꾸는 명령이 없어서 `Checkout.amount`와 `total`이 어긋날 경로가 없다. `ChangeOrderLine`을 넣는 PR이 `OPEN`에서만 허용하도록 막는다.
- `Order.restore`에 `checkouts` 인자를 더한다. 기존 통합 테스트의 helper `withLines`가 `restore`를 직접 부르므로 인자만 맞춘다(범위 조정, 2026-09-21: 컴파일 오류로 발견). `OrderEntity.toDomain()`은 다음 PR이 `t_order_checkout`을 읽을 때까지 빈 목록을 넘긴다. 이 PR에는 `CHECKOUT_REQUESTED` 주문을 저장하는 경로(Facade)가 없어서 빈 목록이 틀린 값이 되는 경우가 없다.
- 실패·재시도·복구, 관측성: 이 PR은 저장·외부 호출·트랜잭션이 없어 해당 없다. 재시도 판정의 도메인 쪽 절반(위 두 번째 분기)만 여기에 있다. 상태로 판정하는 멱등은 순차 재시도만 해소한다. 동시 요청 둘이 모두 `OPEN`을 읽으면 루트의 `status`가 바뀌므로 `@Version`이 두 번째를 거절하고, 그 응답을 500이 아니라 기존 결과로 만드는 처리는 다음 PR이 정한다. 빈 이벤트는 이전 이벤트의 전달 실패를 복구하지 않는다. 그 복구는 Event Publication Registry가 맡는다.

### 합의한 결정 (2026-09-21 grill)
- store 정책, `CheckoutPolicy`, 정책 버전은 만들지 않는다. 합계는 단가 곱 수량의 합이고 `Money`가 통화 자릿수를 강제해서 반올림할 계산이 없다. [도메인 모델](../wiki/domain-model.md) 4.7절 질문 2·3에 걸린다(읽는 코드가 없고 나중에 채울 수 있다). 할인·세금처럼 반올림이 필요한 첫 PR이 store 정책과 정책 버전을 함께 도입한다. 그래서 전이 시그니처는 wiki 4.1절의 `requestCheckout(policy)`가 아니라 `requestCheckout(ids, now)`다.
- 재요청은 상태로 멱등을 판정한다. 요청 키는 두지 않는다. 결제 실패 뒤 재-checkout(2회차)이 생기면 늦게 도착한 1회차 재시도가 2회차를 만들 수 있으므로, 그 PR이 요청 키나 `expectedVersion`을 다시 본다.
- 0원 주문은 거절하지 않는다(위 범위).
- PR은 도메인, 영속화·Facade, HTTP API 세 개로 나눈다. 한 PR이면 약 900줄로 실패 한도(800줄)를 넘는다.

### 범위 조정 (2026-09-21, 제출 전 multi-review 반영)
`CHECKOUT_REQUESTED`는 DB에 이름으로 저장되고 상태 전이는 비즈니스 로직이라 PR 템플릿 기준 🔴이다. multi-review(Claude, Codex 실행. Gemini는 CLI가 없어 미실행)를 돌렸고 둘 다 막는 결함은 없다고 판정했다. 구현 동작은 바뀌지 않는다.
- 재요청 테스트가 같은 인스턴스를 돌려주는지 확인하지 않았다. `assertSame`을 더한다.
- store 정책을 미룬 근거가 "`Money`가 통화 자릿수를 지킨다"인데 전이 테스트가 KRW뿐이었다. 소수 둘째 자리 통화(USD) 전이 테스트를 더한다.
- `(orderId, checkoutNo)`를 `payment`의 멱등 키로 단정한 문장, 동시 요청에서 진 쪽의 결과, 이벤트 전달 실패의 복구, enum 상수의 배포 순서를 위 범위에 적었다.
- `requestCheckout`의 `Order` 생성자 호출을 같은 파일의 다른 호출처럼 이름 있는 인자로 바꾼다.
- 다음 PR이 정할 것을 아래 분할 계획에 옮겼다.

## 제외
- `RequestCheckoutCommand`·`RequestCheckoutResult`, Facade 메서드, `t_order_checkout` 마이그레이션과 엔티티, 트랜잭션. 다음 PR `order-request-checkout-persistence`.
- `POST` endpoint와 REST Docs. 그다음 PR `order-request-checkout-api`.
- Event Publication Registry. `OrderPaymentRequested`를 받는 consumer가 생기는 `payment` PR 전에 도입한다. 그 PR이 이벤트에 처음 실리는 `Money`와 ID value class의 직렬화 형태와 하위 호환을 확인한다.
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
- 현재 상태(`docs/wiki/current-state.md`, 볼트): `order` 모듈 설명에 `CHECKOUT_REQUESTED`, `Checkout`, `requestCheckout`, `OrderPaymentRequested`와 아직 저장 경로가 없다는 점, 테스트 수를 반영한다.
- [도메인 모델](../wiki/domain-model.md): 4.1절의 `order.requestCheckout(policy)` 예시를 실제 시그니처와 정책을 미룬 이유로 고치고, 용어집 `checkout`의 "정책 버전"이 아직 없다는 점을 적는다. 0원 주문을 `payment`가 provider 호출 없이 승인해야 한다는 조건을 컨텍스트 사이 흐름에 적는다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제): 이 spec 링크, 합의한 결정, 요구사항 영향을 적는다.

## 분할 계획
예상 규모는 구현 약 60줄, 테스트 약 90줄, 문서 약 130줄로 총 280줄·10파일 내외다. 경고 한도(600줄·20파일) 안이다. 초과하면 wiki의 도메인 모델 갱신을 다음 PR로 옮긴다. 후속 순서는 `order-request-checkout-persistence`(`V003` `t_order_checkout`, 엔티티, Command·Result, Facade, 재시도 통합 테스트) → `order-request-checkout-api`(endpoint, REST Docs)다. 앞 PR이 머지된 뒤 main에서 다음 PR을 시작한다.

다음 PR `order-request-checkout-persistence`가 정하고 검증할 것:
- `t_order_checkout`의 PK. `Checkout`은 식별자 없는 값 객체다. `(order_id, checkout_no)`를 키로 둘지 `CheckoutId`(UUID v7)를 둘지, 볼트 규칙의 "자식도 도메인 ID를 가진다"에서 값 객체를 예외로 볼지 정한다. `CheckoutId`를 두면 `requestCheckout`의 `ids.next()` 호출 순서가 바뀐다.
- `CHECKOUT_REQUESTED` 주문을 저장했다가 다시 읽으면 `checkouts`가 그대로 복원되는 왕복 통합 테스트. 복원이 빈 목록이면 회차 계산(`checkouts.size + 1`)이 컴파일과 기존 테스트를 통과한 채로 틀린다.
- 재요청을 다시 저장해도 checkout 행이 늘지 않는 것.
- 낙관적 잠금 충돌에서 진 요청이 다시 조회해 기존 checkout 결과를 받는 것.
- checkout을 요청한 주문에 같은 키의 주문 생성 재시도가 오면 `POST /v1/orders`가 201과 `"status": "CHECKOUT_REQUESTED"`를 돌려주게 된다. 지금 wiki는 "재시도도 201과 같은 본문"이라고 적는다. 현재 상태를 돌려주는 것을 계약으로 삼을지, 생성 시점의 `OPEN`을 돌려줄지 정하고 REST Docs의 `data.status` 설명과 wiki를 맞춘다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 1: `OPEN 주문의 requestCheckout은 CHECKOUT_REQUESTED와 1회차 Checkout을 가진 새 주문을 돌려주고 원본은 그대로다` | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`. `requestCheckout`이 자신과 빈 이벤트를 돌려주는 최소 선언 상태에서 실패: `CHECKOUT_REQUESTED`를 기대했는데 `OPEN`이었다 | 같은 명령. 14개 중 실패 0 | `./gradlew test`. 64개 중 실패 0 |
| 인수 조건 2: `requestCheckout은 확정 금액을 담은 OrderPaymentRequested 한 건을 만든다` | 같은 명령. 실패: 이벤트 한 건을 기대했는데 빈 목록이었다 | 같은 명령. 14개 중 실패 0 | `./gradlew test`. 64개 중 실패 0 |
| 인수 조건 3: `합계가 0인 주문도 0원 Checkout으로 전이한다` | 같은 명령. 실패: `CHECKOUT_REQUESTED`를 기대했는데 `OPEN`이었다 | 같은 명령. 14개 중 실패 0 | `./gradlew test`. 64개 중 실패 0 |
| 인수 조건 3 보강(범위 조정): `소수 자리가 있는 통화도 확정 금액이 통화 자릿수를 지킨다` | RED 없음. 구현 뒤에 더한 characterization 테스트라 처음부터 통과했다 | `./gradlew test`. 통과 | `./gradlew test`. 64개 중 실패 0 |
| 인수 조건 4: `이미 CHECKOUT_REQUESTED인 주문의 requestCheckout은 같은 주문과 빈 이벤트를 돌려준다` | 같은 명령. 실패: 첫 전이가 없어서 `CHECKOUT_REQUESTED`를 기대했는데 `OPEN`이었다. 범위 조정으로 더한 `assertSame`은 RED 없이 통과했다 | 같은 명령. 14개 중 실패 0 | `./gradlew test`. 64개 중 실패 0 |

RED 전에 컴파일 오류가 한 번 있었다. `Order.restore`에 `checkouts` 인자를 더하자 `OrderCommandFacadeIntegrationTest`의 helper가 컴파일되지 않았다. 컴파일 오류는 RED가 아니므로 변경 경로를 spec에 먼저 더하고(범위 조정 커밋) helper의 인자를 맞춘 뒤 위 RED를 관측했다.

## 결과
- 인수 조건 1~5를 충족했다. `./gradlew test`는 64개(기존 59, 신규 5) 중 실패 0이다.
- 구현은 `OrderStatus.CHECKOUT_REQUESTED`, 값 객체 `Checkout`, `Order.requestCheckout`, 이벤트 `OrderPaymentRequested`다. `Order`가 `checkouts`를 갖게 되어 `restore`의 인자가 하나 늘었고 `OrderEntity.toDomain()`은 빈 목록을 넘긴다.
- 이 PR만 머지된 상태에서 `CHECKOUT_REQUESTED` 주문을 만들거나 저장하는 프로덕션 경로는 없다. `OrderCommandFacade`의 쓰기는 `create`뿐이고 `Order.create`는 항상 `OPEN`이다. `requestCheckout`을 부르는 프로덕션 코드가 없다.
- multi-review: Claude 84점, Codex 93점, Gemini 미실행(CLI 없음). 둘 다 막는 결함은 없다고 판정했고 합의한 전제 네 가지를 뒤집을 사유도 없다고 봤다. 반영한 것은 위 범위 조정에 적었다. 반영하지 않은 것은 두 가지다. `Order.restore`에 "`CHECKOUT_REQUESTED`인데 `checkouts`가 비면 실패"하는 검사를 넣자는 제안은 다음 PR의 왕복 통합 테스트로 대신한다(`restore`는 불변식을 다시 묻지 않는다는 기존 결정을 유지한다). 테스트용 식별자 생성기가 목록이 마르면 무작위 값을 돌려주는 것은 기존 테스트가 쓰던 방식이라 이 PR에서 바꾸지 않는다.
- `tdd-driver`에 위임하지 않고 주 에이전트가 TDD 절차를 직접 수행했다.
