# PR spec: 결제 승인을 받아 주문을 PAID로 전이한다

## 목적
결제 시도는 승인까지 가지만 주문은 `CHECKOUT_REQUESTED`에 머문다. 원문 [청사진 §3.1](../references/05_Domain_Development_Blueprint.md)의 `PaymentApproved → [MarkOrderPaid] → OrderPaid` 자리다. `PaymentApproved`를 `contract`로 옮길 때 이 소비자를 예고했다.

## 범위
`order`가 `PaymentApproved`를 받아 **최신** checkout 회차의 번호·확정 금액(통화 포함)과 대조한다. 같으면 `PAID`로 전이하고 `OrderPaid`를 낸다.

- **다르면 전이하지 않고 `PAYMENT_APPROVAL_MISMATCH`로 실패한다.** 리스너가 던지므로 `event_publication`에 `FAILED`로 남는다. reconciliation 모듈이 비어 있어 case를 열 곳이 없고, 이 실패 기록이 그 PR이 옮길 흔적이다.
- 이미 `PAID`인데 같은 회차·금액의 승인이 다시 오면 아무것도 하지 않는다. 다른 회차·금액이면 불일치로 실패한다. `paymentId`는 대조하지 않는다 — 같은 회차에 `APPROVED` 승인은 `payment`의 partial unique가 하나로 묶어서, `PAID` 뒤의 같은 승인은 재배달뿐이다. 재결제나 회차 재오픈이 생기면 checkout에 승인 `paymentId`를 둔다(코드에 `ponytail:` 표식).
- `PAID` 주문에 checkout을 다시 요청하면 지금 상태를 그대로 돌려준다.
- 취소 승인(`kind`가 `AUTHORIZATION`이 아님)은 받지 않는다. `PaymentApproved`의 KDoc이 요구한다.
- `OrderPaid`는 받을 쪽(fulfillment)이 없어 `order`의 sealed 계층에 둔다.
- 리스너는 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`다. Facade가 반환하면 커밋 뒤라 로그가 참이다.

계약: `OrderStatus`에 `PAID`가 생긴다. `t_order.status`가 `varchar(32)`라 스키마는 그대로다. API 응답의 `status`에 `PAID`가 나올 수 있다. `PaymentApproved`를 받는 리스너가 생겨 이 이벤트가 처음으로 `event_publication`에 기록되고, `PaymentApprovedListener`의 패키지·클래스·메서드 이름이 `listener_id` 저장 형식이 된다.

복구: 불일치는 `FAILED` 발행 기록으로 남는다. 잠금 대기 초과·DB 장애도 같은 `FAILED`로 남아, 그때는 **결제는 승인됐는데 주문이 `CHECKOUT_REQUESTED`에 머문다.** 자동 재시도가 없어 사람이 봐야 드러난다. 찾는 조건은 `listener_id like '%PaymentApprovedListener%' and status = 'FAILED'`이고, 예외 메시지에 `orderId`와 `checkoutNo`가 있다.

관측성: 전이 뒤 로그 한 줄.

## 제외
- reconciliation case. 모듈이 비어 있다.
- 거절·미확인을 받아 주문을 바꾸는 것. 그 사실은 `payment` 안에 있어 `contract`로 옮겨야 하고, 옮기면 저장 형식이 바뀐다.
- `OrderPaid`의 소비자(주방 티켓).
- 승인의 `storeId`·`terminalId` 대조. `payment`가 이 주문의 `OrderPaymentRequested`에서 옮긴 값이라 모듈 안의 사실이다.
- `contract.PaymentApproved` KDoc의 "reconciliation case를 연다"는 목표 문장이다. 지금은 `FAILED` 기록이 그 자리를 대신한다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2다.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/order/**
- docs/spec/2026-09-23-order-mark-paid.md

## 인수 조건
1. 확정값과 같은 승인이 오면 주문이 `PAID`가 되고 `OrderPaid`가 난다.
2. 금액·통화·회차 중 하나라도 다르면 전이하지 않고 `PAYMENT_APPROVAL_MISMATCH`로 실패한다.
3. `PAID` 주문에 같은 승인이 다시 오면 아무것도 하지 않는다.
4. checkout부터 결제 승인, 주문 `PAID`까지 한 흐름이 통합 테스트로 돈다.
5. 기존 테스트가 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.

## Wiki 영향
- 요구사항 변화: 없다. 원문이 정한 흐름이 이어진다. 현재 상태 문서는 볼트에서 갱신한다.

## 분할 계획
12개 파일 / 311줄. 한도 안이다.

## TDD 검증 기록
| 동작 | RED | GREEN |
|---|---|---|
| 승인 대조와 전이 | `markPaid`를 `Transition(this, emptyList())`로 두고 `--tests '*OrderMarkPaidTest'` — 6개 모두 실패 | 구현 뒤 6개 통과 |
| checkout부터 `PAID`까지 | 리스너가 없으면 주문이 `CHECKOUT_REQUESTED`에 머문다 | 리스너 뒤 통과 |

## 결과
209개 통과다.

- 불일치는 `PAYMENT_APPROVAL_MISMATCH`(`UNPROCESSABLE`)로 던진다. 회차를 못 찾거나 금액·통화가 다르면 모두 여기에 걸린다. `OPEN` 주문은 checkout이 없어 같은 길로 거절된다.
- checkout 뒤 주문이 비동기로 `PAID`가 되므로, checkout 직후 상태를 다시 읽는 테스트가 경합에 걸린다. 공유 픽스처 `OrderFixtures`의 기본 합계를 끝자리 2(결과 미확인)로 바꿔 한 곳에서 막았고, JSON을 직접 쓰는 API 테스트 둘도 같은 금액을 쓴다.
- 불일치가 실제로 `FAILED` 발행 기록으로 남고 주문이 그대로인 것을 통합 테스트가 고정한다.
- REST Docs의 `status` 설명에 `PAID`를 더했다.
