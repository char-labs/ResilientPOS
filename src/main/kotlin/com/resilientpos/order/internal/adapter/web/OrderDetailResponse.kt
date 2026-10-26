package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderDetailView
import com.resilientpos.order.OrderStatus
import java.time.Instant
import java.util.UUID

/**
 * `GET /v1/orders/{orderId}`의 성공 응답 본문. 금액은 주문 통화 기준 decimal string이고 `currency`는 주문 하나에 하나다.
 */
data class OrderDetailResponse(
    val orderId: UUID,
    val clientOrderId: UUID,
    val terminalId: UUID,
    val status: OrderStatus,
    val version: Long,
    val currency: String,
    val total: String,
    val occurredAt: Instant,
    val lines: List<Line>,
    val checkouts: List<Checkout>,
) {
    data class Line(
        val productCode: String,
        val productName: String,
        val unitPrice: String,
        val quantity: Int,
    )

    data class Checkout(
        val checkoutNo: Int,
        val amount: String,
    )

    companion object {
        fun from(view: OrderDetailView) = OrderDetailResponse(
            orderId = view.orderId.value,
            clientOrderId = view.clientOrderId.value,
            terminalId = view.terminalId.value,
            status = view.status,
            version = view.version,
            currency = view.currency.currencyCode,
            total = view.total.plain(),
            occurredAt = view.occurredAt,
            lines = view.lines.map { Line(it.productCode, it.productName, it.unitPrice.plain(), it.quantity) },
            checkouts = view.checkouts.map { Checkout(it.checkoutNo, it.amount.plain()) },
        )

        private fun Money.plain(): String = amount.toPlainString()
    }
}
