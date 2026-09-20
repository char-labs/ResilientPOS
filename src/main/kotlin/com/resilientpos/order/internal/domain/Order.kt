package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderEvent
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency

/**
 * 주문 aggregate 루트. line과 lifecycle을 소유하고 생성 불변식을 검증한다.
 */
class Order private constructor(
    val id: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val clientOrderId: ClientOrderId,
    val currency: Currency,
    val status: OrderStatus,
    val lines: List<OrderLine>,
) {

    val total: Money get() = lines.fold(Money.zero(currency)) { acc, line -> acc + line.amount }

    override fun equals(other: Any?) = other is Order && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val EMPTY_ORDER = "EMPTY_ORDER"
        const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"

        /**
         * 이미 저장된 주문을 그대로 재구성한다. 생성 불변식은 최초 `create` 때 검증됐으므로 다시 묻지 않는다.
         * persistence adapter의 `toDomain()`만 부른다.
         */
        fun restore(
            id: OrderId,
            storeId: StoreId,
            terminalId: TerminalId,
            clientOrderId: ClientOrderId,
            currency: Currency,
            status: OrderStatus,
            lines: List<OrderLine>,
        ): Order = Order(id, storeId, terminalId, clientOrderId, currency, status, lines)

        fun create(command: CreateOrderCommand, ids: IdentifierGenerator, now: Instant): Transition<Order, OrderEvent> {
            if (command.lines.isEmpty()) {
                throw DomainException(EMPTY_ORDER, "주문에는 line이 1개 이상 필요합니다")
            }
            if (command.lines.any { it.unitPrice.currency != command.currency }) {
                throw DomainException(CURRENCY_MISMATCH, "line 통화가 주문 통화와 다릅니다")
            }

            val orderId = OrderId(ids.next())
            val order = Order(
                id = orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                clientOrderId = command.clientOrderId,
                currency = command.currency,
                status = OrderStatus.OPEN,
                lines = command.lines.map { OrderLine(OrderLineId(ids.next()), it.productCode, it.productName, it.unitPrice, it.quantity) },
            )
            val event = OrderCreated(
                eventId = ids.next(),
                occurredAt = now,
                orderId = orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                clientOrderId = command.clientOrderId,
            )
            return Transition(order, listOf(event))
        }
    }
}
