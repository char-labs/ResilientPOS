package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.order.CheckoutId
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderEvent
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.OrderPaymentRequested
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
    val checkouts: List<Checkout>,
) {

    val total: Money get() = lines.fold(Money.zero(currency)) { acc, line -> acc + line.amount }

    override fun equals(other: Any?) = other is Order && other.id == id
    override fun hashCode() = id.hashCode()

    /**
     * 저장할 수 없는 합계를 가진 주문을 만들지 않는다. 범위 검사는 `Money`가 하고 여기서는 평가만 한다.
     * `total`은 접근할 때마다 계산하는 getter라 아무도 읽지 않으면 검사도 일어나지 않는다. 그러면 범위를 넘는
     * 주문이 커밋된 뒤 checkout에서야 드러나고 복구할 수 없다. line을 바꾸는 명령을 더하는 PR은 그 경로에서도 부른다.
     */
    private fun requireStorableTotal() {
        total
    }

    /**
     * 같은 `(terminalId, clientOrderId)`로 다시 온 command가 최초 요청과 같은 내용인지 검증한다.
     */
    fun verifySamePayload(command: CreateOrderCommand) {
        // ponytail: 저장된 현재 상태와 비교한다. line·storeId·currency를 바꾸는 명령이 생기면 현재 상태가
        // 최초 요청과 달라지므로 그 PR에서 최초 요청 기록(payload hash)을 도입한다.
        val storedLines = lines.map { CreateOrderLine(it.productCode, it.productName, it.unitPrice, it.quantity) }
        val same = storeId == command.storeId &&
            currency == command.currency &&
            storedLines.groupingBy { it }.eachCount() == command.lines.groupingBy { it }.eachCount()
        if (!same) {
            throw DomainException(
                IDEMPOTENCY_CONFLICT,
                "같은 clientOrderId로 내용이 다른 주문 생성 요청이 이미 처리되었습니다",
                DomainErrorKind.CONFLICT,
            )
        }
    }

    /**
     * 결제를 요청하는 시점의 금액을 다음 회차 checkout으로 확정한다.
     * 이미 요청된 주문이면 같은 주문과 빈 이벤트를 돌려줘서 재시도가 이벤트를 두 번 내지 않게 한다.
     */
    fun requestCheckout(ids: IdentifierGenerator, now: Instant): Transition<Order, OrderEvent> =
        when (status) {
            OrderStatus.OPEN -> {
                val checkout = Checkout(CheckoutId(ids.next()), checkouts.size + 1, total)
                val requested = Order(
                    id = id,
                    storeId = storeId,
                    terminalId = terminalId,
                    clientOrderId = clientOrderId,
                    currency = currency,
                    status = OrderStatus.CHECKOUT_REQUESTED,
                    lines = lines,
                    checkouts = checkouts + checkout,
                )
                val event = OrderPaymentRequested(
                    eventId = ids.next(),
                    occurredAt = now,
                    orderId = id,
                    storeId = storeId,
                    terminalId = terminalId,
                    checkoutNo = checkout.checkoutNo,
                    amount = checkout.amount,
                )
                Transition(requested, listOf(event))
            }
            OrderStatus.CHECKOUT_REQUESTED -> Transition(this, emptyList())
        }

    companion object {
        const val EMPTY_ORDER = "EMPTY_ORDER"
        const val TOO_MANY_ORDER_LINES = "TOO_MANY_ORDER_LINES"

        /**
         * 영수증 한 장에 담기는 품목 수의 상한. line 하나마다 `t_order_line` 행이 하나씩 들어가고
         * 합계를 접는 비용도 개수를 따라가므로, 상한이 없으면 요청 하나가 쓰는 일의 양에 끝이 없다.
         * 소매 영수증은 수십 건이고 도매·창고 주문에 여유를 둬서 500으로 잡았다.
         * 이보다 큰 주문이 실제로 필요해지면 주문을 나누는 대신 이 값을 올릴지를 그 PR이 정한다.
         */
        const val MAX_LINES = 500
        const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
        const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
        const val ORDER_NOT_FOUND = "ORDER_NOT_FOUND"

        /**
         * 이미 저장된 주문을 그대로 재구성한다. 주문 단위 생성 불변식은 최초 `create` 때 검증됐으므로 다시 묻지 않는다.
         * `lines`의 `OrderLine` 생성자 불변식은 재구성 때도 실행된다.
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
            checkouts: List<Checkout>,
        ): Order = Order(id, storeId, terminalId, clientOrderId, currency, status, lines, checkouts)

        fun create(command: CreateOrderCommand, ids: IdentifierGenerator, now: Instant): Transition<Order, OrderEvent> {
            if (command.lines.isEmpty()) {
                throw DomainException(EMPTY_ORDER, "주문에는 line이 1개 이상 필요합니다")
            }
            if (command.lines.size > MAX_LINES) {
                throw DomainException(TOO_MANY_ORDER_LINES, "주문 line은 ${MAX_LINES}개를 넘을 수 없습니다")
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
                checkouts = emptyList(),
            )
            order.requireStorableTotal()

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
