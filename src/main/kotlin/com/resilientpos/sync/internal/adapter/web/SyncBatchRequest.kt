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
import java.time.Instant
import java.util.UUID

/**
 * `POST /v1/terminals/{terminalId}/sync`의 요청 본문. 형식 오류는 Jackson 역직렬화가 거절하므로 여기서는 검증하지 않는다.
 * 항목의 `createdAt`(ISO-8601 offset 시각)은 선택이고 저장하지 않으며 수렴 시간 metric에만 쓰인다. payload에 넣지 않아 payload hash에 영향이 없다.
 * 항목의 `order`는 `POST /v1/orders` 본문에서 `terminalId`를 뺀 모양이다. 단말 ID는 경로에서만 온다. 두 입구의 수용 범위는 다르다(`quantity`는 여기서 소수도 받아 그 항목을 거절한다).
 * 항목은 `kind`로 종류가 갈린다. `kind`가 없거나 모르는 값이면 기본 종류로 빠지지 않고 역직렬화가 거절해 배치 전체가 400이다.
 * 통화·수량·단가를 `Currency`·`Int`·`Money`로 바꾸지 않고 raw로 받는다. 이 규칙 위반은 배치 전체의 400이 아니라 그 항목의 `REJECTED`여야 하므로 Facade가 항목 안에서 바꾼다.
 */
data class SyncBatchRequest(
    val operations: List<SyncOperationRequest>,
) {
    fun toCommand(terminalId: TerminalId, storeId: StoreId): SyncCommand.ReceiveBatch =
        SyncCommand.ReceiveBatch(terminalId, storeId, operations.map { it.toCommand() })
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
        val createdAt: Instant? = null,
    ) : SyncOperationRequest {
        override fun toCommand() = SyncCommand.ReceiveBatch.Operation(operationId, sequence, order.toPayload(), createdAt)
    }

    /**
     * 단말은 서버 `orderId`를 모르므로 앞선 `CREATE_ORDER`의 `clientOrderId`로 주문을 가리키고, 단말이 본 합계를 `amount`(주문 통화의
     * decimal string)로 싣는다. `amount`가 없으면 역직렬화가 거절해 배치 전체가 400이다.
     */
    data class RequestCheckout(
        val operationId: UUID,
        val sequence: Long,
        val clientOrderId: UUID,
        val amount: BigDecimal,
        val createdAt: Instant? = null,
    ) : SyncOperationRequest {
        override fun toCommand() =
            SyncCommand.ReceiveBatch.Operation(operationId, sequence, CheckoutPayload(ClientOrderId(clientOrderId), amount), createdAt)
    }
}

data class SyncOrderRequest(
    val storeId: UUID,
    val clientOrderId: UUID,
    val currency: String,
    val lines: List<SyncOrderLineRequest>,
) {
    fun toPayload(): OrderPayload = OrderPayload(
        storeId = StoreId(storeId),
        clientOrderId = ClientOrderId(clientOrderId),
        currency = currency,
        lines = lines.map { it.toPayload() },
    )
}

/** `unitPrice`는 주문의 `currency`를 따르는 decimal string이다. `quantity`는 정수가 아닌 값도 그대로 받는다(Jackson이 자르지 않는다). */
data class SyncOrderLineRequest(
    val productCode: String,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: BigDecimal,
) {
    fun toPayload(): OrderPayload.Line = OrderPayload.Line(productCode, productName, unitPrice, quantity)
}
