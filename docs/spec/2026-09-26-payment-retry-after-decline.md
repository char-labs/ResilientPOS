# PR spec: 거절된 결제 시도를 같은 checkout 회차에서 다시 시도한다

## 목적
provider가 결제를 거절하면 시도는 `DECLINED`, 주문은 `CHECKOUT_REQUESTED`에 머물고 더 나아갈 길이 없다. `createAttempt`는 같은 `(orderId, checkoutNo)`에 결말과 무관하게 기존 시도를 돌려주고, `order`는 `CHECKOUT_REQUESTED`에서 checkout을 다시 열지 않는다. [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md)이 "거절 뒤 재결제는 DB가 허용하지만 그 입구는 사용자가 명시적으로 요청하는 별도 명령"으로 남겨 둔 자리다. 그 명령을 만든다.

## 범위
소유 모듈은 `payment`다. `order`와 `contract`는 바꾸지 않는다.

**같은 checkout 회차의 새 시도로 재결제한다.** 새 checkout 회차를 열지 않는다. 확정 금액은 회차 안에서 바뀌지 않고, checkout 재요청의 상태 멱등(늦게 도착한 재요청이 2회차를 만드는 문제)을 건드리지 않는다. partial unique `ux_payment_attempt_inflight_authorization`이 `DECLINED`를 술어에서 뺀 것이 이 경로를 위해서다. `order`는 거절 사실을 받지 않으므로 `PaymentEvent.Declined`는 `payment`에 남고 이벤트 저장 형식은 그대로다.

**명령은 거절된 시도를 가리킨다.** `PaymentManagement.retryAuthorization(PaymentCommand.RetryAuthorization(paymentId))`이고 결과는 `PaymentCommand.RetryAuthorization.Result(paymentId, status)`로 새(또는 이미 있는) 시도를 돌려준다. 주문이 아니라 시도를 키로 삼는 이유는 응답을 잃은 재요청과 새 재결제 의도를 구분하기 위해서다.

| 대상 시도 | 결과 |
|---|---|
| 없음 | `PAYMENT_ATTEMPT_NOT_FOUND` |
| 그 회차의 최신 승인 시도가 아님 | 이미 재결제한 것이다. 최신 시도를 돌려주고 아무것도 만들지 않는다 |
| 최신이고 `DECLINED` | 같은 주문·매장·단말·회차·금액으로 `REQUESTED` 시도를 새로 만들고 `PaymentEvent.AttemptCreated`를 낸다 |
| 최신이고 `REQUESTED`·`UNKNOWN`·`APPROVED`, 또는 취소 시도 | `PAYMENT_NOT_RETRYABLE`(`UNPROCESSABLE`)로 거절한다. 메시지에 현재 상태를 적는다 |

`UNKNOWN`에서 막는 이유는 돈이 이미 움직였을 수 있어서다. 조회·reconciliation으로 수렴한 뒤에야 재결제를 판단할 수 있다.

**새 시도는 aggregate가 만든다.** `PaymentAttempt.retry(ids, now)`가 `DECLINED` 승인 시도에서만 새 시도와 `AttemptCreated`를 돌려주고 그 밖에는 `PAYMENT_NOT_RETRYABLE`로 거절한다. provider 호출은 기존 `PaymentAttemptCreatedListener`가 이어받는다. 재결제 명령은 provider를 부르지 않는다.

**겹친 재결제는 대상 행 잠금으로 직렬화한다.** Facade는 `authorize`처럼 `@Transactional(propagation = NEVER)`이고 `TransactionOperations`로 연 트랜잭션 안에서 대상 시도를 `findByIdForUpdate`로 잠근 뒤 그 회차의 최신 승인 시도를 읽는다. 진 쪽은 앞 트랜잭션의 커밋을 기다렸다가 새로 생긴 시도를 최신으로 보고 그것을 돌려받는다. partial unique는 마지막 방어선으로 남는다. 잠금 대기는 기존과 같은 3초다.

**최신 시도 조회를 더한다.** 그 회차의 승인 시도를 `id` 내림차순으로 하나 읽는 파생 조회다(UUID v7이라 곧 생성 역순). 재배달 입구 `findAuthorization`(가장 먼저 만든 것)은 그대로 둔다. 재배달은 새 시도를 만들지 않는다는 판단만 하면 되고 어느 시도를 돌려주는지는 쓰이지 않는다.

**`order.markPaid`에 `paymentId` 대조를 들이지 않는다.** 재결제가 생겨도 한 회차에 `APPROVED` 승인이 둘이 되는 길이 없다. partial unique가 거절되지 않은 승인 시도를 하나로 묶고, `DECLINED`는 `applyAuthorization`이 다시 바꾸지 못하며, `UNKNOWN`에서는 재결제를 막는다. `PAID` 뒤 같은 회차의 승인은 여전히 재배달뿐이다. 코드의 `ponytail:` 표식은 해제 조건을 "`UNKNOWN`에서 재결제를 허용하거나 결말이 난 시도를 바꾸는 경로(reconciliation)가 생기면"으로 고친다. 그 전제를 재결제 통합 테스트(거절 → 재결제 → 승인 → 주문 `PAID`)가 고정한다.

계약: 새 오류 code `PAYMENT_NOT_RETRYABLE`이 생긴다. HTTP endpoint가 없어 아직 응답으로 나가지 않는다. `PaymentCommand`에 `RetryAuthorization`이 생긴다. 스키마, 이벤트, `event_publication`의 `listener_id`는 바뀌지 않는다. 한 회차에 승인 시도 행이 여럿 생길 수 있게 되지만 `t_payment_attempt`는 처음부터 그것을 허용했다.

복구: 재결제 트랜잭션이 실패하면 아무것도 남지 않고 호출자가 같은 명령을 다시 보내면 된다. 응답을 잃고 다시 보내도 새 시도는 하나다. 새 시도의 provider 호출 실패는 기존 `PaymentAttemptCreatedListener`의 복구 경로(발행 기록 `FAILED`)를 그대로 따른다. 잠금 대기 3초를 넘기면 `PessimisticLockingFailureException` 계열로 실패하고 아무것도 바꾸지 않는다.

관측성: Facade가 새 시도를 만들면 `declinedPaymentId`와 새 `paymentId`를 로그 한 줄로 남긴다. 트랜잭션을 `TransactionOperations`로 열고 그 반환 뒤에 찍으므로 로그 시점이 곧 커밋 뒤다. 이미 재결제한 요청이면 찍지 않는다.

## 제외
- HTTP endpoint와 REST Docs. 단말 인증이 없어 외부에 노출하지 않는다. 다음 PR이 만든다.
- 결제 시도 조회 API. 단말이 거절 사실과 `paymentId`를 알아낼 길이다. endpoint PR과 함께 정한다.
- `order`가 거절을 받는 것, 새 checkout 회차, `PaymentDeclined`의 `contract` 이동.
- `order.markPaid`의 `paymentId` 대조와 `t_order_checkout` 스키마 변경. 위 범위의 근거.
- `UNKNOWN`의 조회·reconciliation 수렴.
- 거절 사유 저장, 재결제 횟수 상한. 받을 쪽과 근거가 없다.
- `FakePaymentGateway`의 규칙 변경. 재결제 승인 흐름은 테스트 전용 gateway로 검증한다.
- `findAuthorization`의 동시 재배달 경합(`pg_advisory_xact_lock` 표식). 이 PR이 바꾸지 않는다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§4.3이다. 앞 PR은 [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md)과 [order-mark-paid](2026-09-23-order-mark-paid.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/test/kotlin/com/resilientpos/payment/**
- docs/spec/2026-09-26-payment-retry-after-decline.md

## 인수 조건
1. 최신 승인 시도가 `DECLINED`이면 같은 회차·금액의 `REQUESTED` 시도가 새로 생기고 `PaymentEvent.AttemptCreated`가 난다.
2. 새 시도가 provider를 거쳐 승인되면 주문이 `PAID`가 된다(통합 테스트, 테스트 전용 gateway).
3. 이미 재결제한 거절 시도로 다시 요청하면 새 시도를 만들지 않고 최신 시도를 돌려준다.
4. 최신 시도가 `REQUESTED`·`UNKNOWN`·`APPROVED`이면 `PAYMENT_NOT_RETRYABLE`로 거절하고 아무것도 만들지 않는다. 취소 시도도 같다.
5. 없는 시도면 `PAYMENT_ATTEMPT_NOT_FOUND`로 거절한다.
6. 같은 거절 시도에 재결제가 겹쳐도 새 시도는 하나이고 두 호출이 같은 `paymentId`를 돌려받는다.
7. 기존 테스트가 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 동작을 지워 테스트가 실패하는지 본다. 최신 시도 판정을 지우고, 잠금을 빼고, `DECLINED` 검사를 빼서 각각 해당 테스트가 빨개지는지 확인한다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md): 바꾸지 않는다. §4.3의 "거절된 시도는 partial unique의 술어 밖"이 그대로 참이다. 작업 중 같은 파일을 고치던 #57(발행 기록 metric)과도 겹치지 않는다.
- 현재 상태 문서는 볼트에서 갱신한다(재결제 명령이 생겼고 입구는 Facade뿐이라는 사실).
- 요구사항 변화: 거절된 결제를 같은 회차에서 다시 시도할 수 있다. 외부 API와 스키마는 그대로다.

## 분할 계획
예상 10개 파일 안팎 / 약 500줄. 한도 안이다. endpoint와 조회 API는 목표가 달라 다음 PR로 나눈다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 거절된 시도에서 새 시도를 만든다 (인수 조건 1·4 도메인) `PaymentAttemptRetryTest` | `retry`를 `Transition(this, emptyList())`로 두고 `./gradlew test --tests '*PaymentAttemptRetryTest'` — 3개 모두 실패. 새 시도 id가 원래 id였고, 거절 대상 두 테스트는 예외 없이 진행(`actual null`) | 구현 뒤 3개 통과 | `./gradlew test` — 228개 통과 |
| 재결제 명령의 멱등과 거절 (인수 조건 1·3·4·5) `PaymentManagementTest` | Facade를 대상 시도를 그대로 돌려주는 빈 동작으로 두고 `--tests '*PaymentManagementTest'` — 신규 4개 중 3개 실패(새 시도 없음 `List is empty`, 두 거절 테스트 `actual null`). "이미 재결제한 요청" 테스트는 빈 동작에서 저절로 맞았다 | 구현 뒤 11개 통과 | `./gradlew test` — 228개 통과 |
| 거절 뒤 재결제가 승인되어 주문이 PAID가 된다 (인수 조건 2) `PaymentRetryIntegrationTest` | 구현 뒤에 쓴 테스트라 새 시도 생성을 지운 코드로 확인했다 — `주문이 PAID가 된다. 현재: CHECKOUT_REQUESTED`로 실패 | 되돌리면 통과 | `./gradlew test` — 228개 통과 |
| 겹친 재결제가 시도 하나로 수렴한다 (인수 조건 6) `PaymentRetryIntegrationTest` | 대상 잠금을 `findById`로 바꾸면 3회 모두 실패 — 진 쪽이 커밋에서 `ux_payment_attempt_inflight_authorization` 위반(`DataIntegrityViolationException`) | 되돌리면 통과 | `./gradlew test` — 228개 통과 |

통과 뒤 동작을 하나씩 지워 테스트가 다시 빨개지는지 봤다.

| 지운 동작 | 빨개진 테스트 |
|---|---|
| 최신 시도 판정(이미 재결제했으면 최신을 돌려준다) | Facade 단위 `이미 재결제한 거절 시도로 다시 요청하면...` 1개 |
| 대상 행 잠금 | 겹친 재결제 통합 — 3회 모두 |
| 새 시도 생성 | 재결제 승인 통합 |

잠금을 뺐을 때의 실패는 래치로 두 호출을 동시에 출발시킨 결과이고 순서를 보장하지는 않는다. 확률적이지만 3회 모두 실패했다.

## 결과
`./gradlew test` 241개 통과다(신규 9). 구현 중에는 228개였고, #57이 머지된 origin/main(620d450)으로 rebase한 뒤 그 PR의 테스트를 더해 다시 돌렸다. `python3 scripts/check_pr.py --base origin/main` PASS.

- 재결제는 `PaymentAttempt.retry`가 만들고 `PaymentManagement.retryAuthorization`이 잠금·최신 판정·저장·발행을 소유한다. provider 호출은 기존 리스너가 이어받는 것을 통합 테스트가 끝까지 확인한다(거절 → 재결제 → 승인 → 주문 `PAID`, 같은 회차에 시도 행 둘).
- Facade는 `authorize`와 같이 `NEVER`이고 `TransactionOperations`로 트랜잭션을 연다. 범위 절의 "반환 뒤 로그"를 호출자가 아니라 Facade 안에서 커밋 뒤에 찍기 위해서다. 범위 절을 그에 맞게 고쳤다.
- 겹친 재결제에서 진 쪽은 예외 없이 앞 요청이 만든 시도를 돌려받는다. 잠금이 없으면 partial unique가 막아 행은 둘이 되지 않지만 진 쪽이 `DataIntegrityViolationException`을 받는다.
- `order`는 `markPaid`의 `ponytail:` 표식 문구만 바뀌었다. 동작은 그대로다.
- 테스트 전용 gateway는 금액마다 처음 한 번만 거절한다. 테스트마다 금액을 다르게 둔다.
