package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderStatus
import com.resilientpos.order.OrderSummaryView
import java.time.Instant
import java.util.UUID

/** 점주 주문 목록 한 줄. `total`은 주문 통화 기준 decimal string(line 합)이다. */
data class OrderSummaryResponse(
    val orderId: UUID,
    val terminalId: UUID,
    val status: OrderStatus,
    val currency: String,
    val total: String,
    val occurredAt: Instant,
) {
    companion object {
        fun from(view: OrderSummaryView) = OrderSummaryResponse(
            orderId = view.orderId.value,
            terminalId = view.terminalId.value,
            status = view.status,
            currency = view.currency.currencyCode,
            total = view.total.amount.toPlainString(),
            occurredAt = view.occurredAt,
        )
    }
}
