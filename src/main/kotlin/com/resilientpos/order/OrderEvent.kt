package com.resilientpos.order

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `order` 모듈이 발행하는 도메인 이벤트의 닫힌 집합.
 */
sealed interface OrderEvent : DomainEvent

/**
 * 주문이 `OPEN` 상태로 생성됐다는 사실.
 */
data class OrderCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val orderId: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val clientOrderId: ClientOrderId,
) : OrderEvent
