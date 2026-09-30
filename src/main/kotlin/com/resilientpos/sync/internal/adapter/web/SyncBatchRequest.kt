package com.resilientpos.sync.internal.adapter.web

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.resilientpos.order.ClientOrderId
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.application.SyncCommand
import com.resilientpos.sync.internal.domain.CheckoutPayload
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * `POST /v1/terminals/{terminalId}/sync`의 요청 본문. 형식 오류는 Jackson 역직렬화가 거절하므로 여기서는 검증하지 않는다.
 * 항목의 `order`는 `POST /v1/orders` 본문에서 `terminalId`를 뺀 모양이다. 단말 ID는 경로에서만 온다.
 * 항목은 `kind`로 종류가 갈린다. `kind`가 없거나 모르는 값이면 기본 종류로 빠지지 않고 역직렬화가 거절해 배치 전체가 400이다.
 * 값을 `Money`로 바꾸지 않는다. 금액 규칙 위반은 배치 전체의 400이 아니라 그 항목의 `REJECTED`여야 하므로 Facade가 항목 안에서 바꾼다.
 */
data class SyncBatchRequest(
    val operations: List<SyncOperationRequest>,
) {
    fun toCommand(terminalId: TerminalId): SyncCommand.ReceiveBatch =
        SyncCommand.ReceiveBatch(terminalId, operations.map { it.toCommand() })
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(SyncOperationRequest.CreateOrder::class, name = "CREATE_ORDER"),
    JsonSubTypes.Type(SyncOperationRequest.RequestCheckout::class, name = "REQUEST_CHECKOUT"),
)
sealed interface SyncOperationRequest {
    fun toCommand(): SyncCommand.ReceiveBatch.Operation

    /** `order`는 이 종류에만 있다. */
    data class CreateOrder(
        val operationId: UUID,
        val sequence: Long,
        val order: SyncOrderRequest,
    ) : SyncOperationRequest {
        override fun toCommand() = SyncCommand.ReceiveBatch.Operation(operationId, sequence, order.toPayload())
    }

    /** 단말은 서버 `orderId`를 모르므로 앞선 `CREATE_ORDER`의 `clientOrderId`로 주문을 가리킨다. */
    data class RequestCheckout(
        val operationId: UUID,
        val sequence: Long,
        val clientOrderId: UUID,
    ) : SyncOperationRequest {
        override fun toCommand() =
            SyncCommand.ReceiveBatch.Operation(operationId, sequence, CheckoutPayload(ClientOrderId(clientOrderId)))
    }
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
