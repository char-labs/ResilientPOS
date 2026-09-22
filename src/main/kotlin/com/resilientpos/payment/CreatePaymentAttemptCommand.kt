package com.resilientpos.payment

import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId

/**
 * 한 주문의 `checkoutNo`회차 확정 금액으로 결제 시도를 만든다.
 * `order`가 낸 `OrderPaymentRequested`의 값을 그대로 옮긴 것이고, `payment`는 `order`의 테이블을 읽지 않는다.
 */
data class CreatePaymentAttemptCommand(
    val orderId: OrderId,
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
    val attemptNo: Int,
    val status: PaymentAttemptStatus,
)
