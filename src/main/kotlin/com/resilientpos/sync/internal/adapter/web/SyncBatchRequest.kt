package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.application.SyncCommand
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * `POST /v1/terminals/{terminalId}/sync`의 요청 본문. 형식 오류는 Jackson 역직렬화가 거절하므로 여기서는 검증하지 않는다.
 * 항목의 `order`는 `POST /v1/orders` 본문에서 `terminalId`를 뺀 모양이다. 단말 ID는 경로에서만 온다.
 */
data class SyncBatchRequest(
    val operations: List<SyncOperationRequest>,
) {
    fun toCommand(terminalId: TerminalId): SyncCommand.ReceiveBatch =
        SyncCommand.ReceiveBatch(terminalId, operations.map { it.toCommand(terminalId) })
}

data class SyncOperationRequest(
    val operationId: UUID,
    val sequence: Long,
    val order: SyncOrderRequest,
) {
    fun toCommand(terminalId: TerminalId): SyncCommand.ReceiveBatch.Operation =
        SyncCommand.ReceiveBatch.Operation(operationId, sequence, order.toCommand(terminalId))
}

data class SyncOrderRequest(
    val storeId: UUID,
    val clientOrderId: UUID,
    val currency: Currency,
    val lines: List<SyncOrderLineRequest>,
) {
    fun toCommand(terminalId: TerminalId): OrderCommand.Create = OrderCommand.Create(
        storeId = StoreId(storeId),
        terminalId = terminalId,
        clientOrderId = ClientOrderId(clientOrderId),
        currency = currency,
        lines = lines.map { it.toCommand(currency) },
    )
}

/** `unitPrice`는 주문의 `currency`를 따르는 decimal string이다. */
data class SyncOrderLineRequest(
    val productCode: String,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
) {
    fun toCommand(currency: Currency): OrderCommand.Create.Line =
        OrderCommand.Create.Line(productCode, productName, Money.of(unitPrice, currency), quantity)
}
