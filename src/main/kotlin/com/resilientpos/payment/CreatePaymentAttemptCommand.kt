package com.resilientpos.payment

import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * 한 주문의 `checkoutNo`회차 확정 금액으로 결제 시도를 만든다.
 * `order`가 낸 `OrderPaymentRequested`의 값을 그대로 옮긴 것이고, `payment`는 `order`의 테이블을 읽지 않는다. *
 * `orderId`는 `order.OrderId`가 아니라 raw `UUID`다. 모듈 간선 `payment -> order`는 리스너가
 * `OrderPaymentRequested`를 받으면서 이미 생겼으므로 이 선택이 그 간선을 막지는 않는다.
 * **공개 계약을 `order` 타입에서 떼어 놓는 것**이 목적이다. 순환을 푸는 PR이 `OrderPaymentRequested`를
 * 다른 곳으로 옮기더라도 `payment`의 공개 타입은 손대지 않아도 된다.
 */
data class CreatePaymentAttemptCommand(
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val amount: Money,
)

/**
 * 시도 생성 결과. 0원 주문이면 생성과 동시에 [PaymentAttemptStatus.APPROVED]다.
 */
data class CreatePaymentAttemptResult(
    val paymentId: PaymentId,
    val status: PaymentAttemptStatus,
)
