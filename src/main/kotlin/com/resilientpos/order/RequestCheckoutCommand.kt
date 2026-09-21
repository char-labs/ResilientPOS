package com.resilientpos.order

import com.resilientpos.foundation.Money

/**
 * 주문의 결제 요청. `OrderCommandFacade.requestCheckout`의 공개 입력이다.
 */
data class RequestCheckoutCommand(
    val orderId: OrderId,
)

/**
 * 결제 요청 결과. `checkoutNo`회차에 확정한 금액이 `amount`다. 재요청이면 이미 확정된 값을 그대로 담는다.
 */
data class RequestCheckoutResult(
    val orderId: OrderId,
    val status: OrderStatus,
    val checkoutNo: Int,
    val amount: Money,
)
