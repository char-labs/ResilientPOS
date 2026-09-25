package com.resilientpos.order

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `order` 모듈이 발행하는 도메인 이벤트의 닫힌 집합. 모듈을 건너가는 이벤트는 `contract`가 갖는다
 * (`PAID` 전이가 내는 `contract.OrderPaid`가 그 예다).
 */
sealed interface OrderEvent : DomainEvent {
    /**
     * 주문이 `OPEN` 상태로 생성됐다는 사실.
     */
    data class Created(
        override val eventId: UUID,
        override val occurredAt: Instant,
        val orderId: OrderId,
        val storeId: StoreId,
        val terminalId: TerminalId,
        val clientOrderId: ClientOrderId,
    ) : OrderEvent
}
