package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderStatus
import java.util.UUID

/**
 * `POST /v1/orders/{orderId}/checkout`의 성공 응답 본문. `amount`는 주문 통화 기준 decimal string이다.
 */
data class RequestCheckoutResponse(
    val orderId: UUID,
    val status: OrderStatus,
    val checkoutNo: Int,
    val amount: String,
    val currency: String,
) {
    companion object {
        fun from(result: OrderCommand.RequestCheckout.Result) = RequestCheckoutResponse(
            orderId = result.orderId.value,
            status = result.status,
            checkoutNo = result.checkoutNo,
            amount = result.amount.amount.toPlainString(),
            currency = result.amount.currency.currencyCode,
        )
    }
}
