package com.resilientpos.order

import com.resilientpos.foundation.Money
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency

/**
 * 주문 단건의 읽기 투영. aggregate를 거치지 않고 저장된 행에서 바로 만든다.
 * `total`은 컬럼이 아니라 line 합이다. `version`은 aggregate version으로, 단말이 바뀌었는지 비교하는 값이다.
 */
data class OrderDetailView(
    val orderId: OrderId,
    val clientOrderId: ClientOrderId,
    val terminalId: TerminalId,
    val status: OrderStatus,
    val version: Long,
    val currency: Currency,
    val total: Money,
    val occurredAt: Instant,
    val lines: List<Line>,
    val checkouts: List<Checkout>,
) {
    data class Line(
        val productCode: String,
        val productName: String,
        val unitPrice: Money,
        val quantity: Int,
    )

    data class Checkout(
        val checkoutNo: Int,
        val amount: Money,
    )
}
