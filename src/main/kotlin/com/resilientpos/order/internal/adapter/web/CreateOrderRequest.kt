package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * `POST /v1/orders`의 요청 본문. 형식 오류는 Jackson 역직렬화가 거절하므로 여기서는 검증하지 않는다.
 */
data class CreateOrderRequest(
    val storeId: UUID,
    val terminalId: UUID,
    val clientOrderId: UUID,
    val currency: Currency,
    val lines: List<CreateOrderLineRequest>,
) {
    fun toCommand(): OrderCommand.Create = OrderCommand.Create(
        storeId = StoreId(storeId),
        terminalId = TerminalId(terminalId),
        clientOrderId = ClientOrderId(clientOrderId),
        currency = currency,
        lines = lines.map { it.toCommand(currency) },
        occurredAt = null,
    )
}

/**
 * 요청 본문의 line. `unitPrice`는 주문의 `currency`를 따르는 decimal string이다.
 */
data class CreateOrderLineRequest(
    val productCode: String,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
) {
    fun toCommand(currency: Currency): OrderCommand.Create.Line =
        OrderCommand.Create.Line(productCode, productName, Money.of(unitPrice, currency), quantity)
}
