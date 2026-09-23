package com.resilientpos.order

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `order` 모듈이 발행하는 도메인 이벤트의 닫힌 집합.
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

    /**
     * 주문의 checkout 회차가 결제 승인과 대조되어 `PAID`가 됐다는 사실. 받을 쪽(fulfillment)이 없어 `order`에 남는다.
     */
    data class Paid(
        override val eventId: UUID,
        override val occurredAt: Instant,
        val orderId: OrderId,
        val checkoutNo: Int,
        val amount: Money,
    ) : OrderEvent
}
