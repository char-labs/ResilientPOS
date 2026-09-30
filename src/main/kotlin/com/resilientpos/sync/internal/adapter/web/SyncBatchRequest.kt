package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.order.ClientOrderId
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.application.SyncCommand
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * `POST /v1/terminals/{terminalId}/sync`의 요청 본문. 형식 오류는 Jackson 역직렬화가 거절하므로 여기서는 검증하지 않는다.
 * 항목의 `order`는 `POST /v1/orders` 본문에서 `terminalId`를 뺀 모양이다. 단말 ID는 경로에서만 온다.
 * 값을 `Money`로 바꾸지 않는다. 금액 규칙 위반은 배치 전체의 400이 아니라 그 항목의 `REJECTED`여야 하므로 Facade가 항목 안에서 바꾼다.
 */
data class SyncBatchRequest(
    val operations: List<SyncOperationRequest>,
) {
    fun toCommand(terminalId: TerminalId): SyncCommand.ReceiveBatch =
        SyncCommand.ReceiveBatch(terminalId, operations.map { it.toCommand() })
}

data class SyncOperationRequest(
    val operationId: UUID,
    val sequence: Long,
    val order: SyncOrderRequest,
) {
    fun toCommand(): SyncCommand.ReceiveBatch.Operation =
        SyncCommand.ReceiveBatch.Operation(operationId, sequence, order.toPayload())
}

data class SyncOrderRequest(
    val storeId: UUID,
    val clientOrderId: UUID,
    val currency: Currency,
    val lines: List<SyncOrderLineRequest>,
) {
    fun toPayload(): OrderPayload = OrderPayload(
        storeId = StoreId(storeId),
        clientOrderId = ClientOrderId(clientOrderId),
        currency = currency,
        lines = lines.map { it.toPayload() },
    )
}

/** `unitPrice`는 주문의 `currency`를 따르는 decimal string이다. */
data class SyncOrderLineRequest(
    val productCode: String,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
) {
    fun toPayload(): OrderPayload.Line = OrderPayload.Line(productCode, productName, unitPrice, quantity)
}
