package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.order.CheckoutId
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderEvent
import com.resilientpos.foundation.DomainEvent
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderLineId
import com.resilientpos.contract.OrderPaid
import com.resilientpos.contract.OrderPaymentRequested
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
    fun verifySamePayload(command: OrderCommand.Create) {
        // ponytail: 저장된 현재 상태와 비교한다. line·storeId·currency를 바꾸는 명령이 생기면 현재 상태가
        // 최초 요청과 달라지므로 그 PR에서 최초 요청 기록(payload hash)을 도입한다.
        val storedLines = lines.map { OrderCommand.Create.Line(it.productCode, it.productName, it.unitPrice, it.quantity) }
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
    fun requestCheckout(ids: IdentifierGenerator, now: Instant): Transition<Order, DomainEvent> =
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
                    orderId = id.value,
                    storeId = storeId,
                    terminalId = terminalId,
                    checkoutNo = checkout.checkoutNo,
                    amount = checkout.amount,
                )
                Transition(requested, listOf(event))
            }
            OrderStatus.CHECKOUT_REQUESTED, OrderStatus.PAID -> Transition(this, emptyList())
        }

    /**
     * 결제 승인을 최신 checkout 회차의 확정 금액(통화 포함)과 대조해 `PAID`로 전이한다.
     * 다르면 전이하지 않고 거절한다 — 리스너의 실패 기록이 reconciliation이 옮길 흔적이 된다.
     * 이미 `PAID`인 주문에 같은 승인이 다시 오면 아무것도 하지 않는다.
     */
    fun markPaid(checkoutNo: Int, amount: Money, ids: IdentifierGenerator, now: Instant): Transition<Order, DomainEvent> {
        // ponytail: 같은 회차의 두 번째 승인은 payment의 partial unique가 막아 PAID 뒤의 같은 승인은 재배달뿐이다.
        // 거절 뒤 재결제도 같은 회차에 새 시도를 넣을 뿐이고 DECLINED는 다시 승인되지 않으며 UNKNOWN은 재결제를 막는다.
        // UNKNOWN 재결제를 허용하거나 결말이 난 시도를 바꾸는 경로(reconciliation)가 생기면 checkout에 승인 paymentId를 두고 대조한다.
        val latest = checkouts.lastOrNull()
        if (latest?.checkoutNo != checkoutNo || latest.amount != amount) {
            throw DomainException(
                PAYMENT_APPROVAL_MISMATCH,
                "결제 승인이 주문의 확정값과 다릅니다 orderId=${id.value} " +
                    "approved=$checkoutNo/$amount expected=${latest?.checkoutNo}/${latest?.amount}",
                DomainErrorKind.UNPROCESSABLE,
            )
        }
        if (status == OrderStatus.PAID) return Transition(this, emptyList())

        val paid = Order(
            id = id,
            storeId = storeId,
            terminalId = terminalId,
            clientOrderId = clientOrderId,
            currency = currency,
            status = OrderStatus.PAID,
            lines = lines,
            checkouts = checkouts,
        )
        return Transition(paid, listOf(OrderPaid(ids.next(), now, id.value, storeId, terminalId, checkoutNo, amount)))
    }

    companion object {
        const val EMPTY_ORDER = "EMPTY_ORDER"
        const val PAYMENT_APPROVAL_MISMATCH = "PAYMENT_APPROVAL_MISMATCH"
        const val TOO_MANY_ORDER_LINES = "TOO_MANY_ORDER_LINES"

        /**
         * 영수증 한 장에 담기는 품목 수의 상한. line 하나마다 `t_order_line` INSERT가 한 번씩 나가고
         * 지금은 JDBC 배치가 없어서 DB 왕복도 개수를 그대로 따라간다. 상한이 없으면 요청 하나가 쓰는 일의 양에 끝이 없다.
         * 소매 영수증은 수십 건이고 도매·창고 주문에 여유를 둬서 500으로 잡았다.
         * 이보다 큰 주문이 실제로 필요해지면 주문을 나누는 대신 이 값을 올릴지를 그 PR이 정한다.
         * 본문 크기 상한과 함께 보는 값이다. 최악의 정상 주문(모든 필드가 컬럼 상한)이 1 MiB의 절반이라
         * 이 값을 두 배로 올리면 정상 주문이 전송 단계에서 끊긴다.
         */
        const val MAX_LINES = 500

        /**
         * line 개수 불변식. `create`만이 아니라 **line을 더하는 명령을 새로 만드는 PR도 이것을 부른다.**
         * `restore`는 부르지 않는다. 이미 저장된 주문은 최초 `create` 때 검증됐고, 나중에 상한을 낮추더라도
         * 저장된 주문이 조회·checkout되지 못하게 막아 세우지 않기 위해서다.
         */
        fun requireLineCountWithinLimit(size: Int) {
            if (size > MAX_LINES) {
                throw DomainException(TOO_MANY_ORDER_LINES, "주문 line은 ${'$'}{MAX_LINES}개를 넘을 수 없습니다")
            }
        }
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

        fun create(command: OrderCommand.Create, ids: IdentifierGenerator, now: Instant): Transition<Order, DomainEvent> {
            if (command.lines.isEmpty()) {
                throw DomainException(EMPTY_ORDER, "주문에는 line이 1개 이상 필요합니다")
            }
            requireLineCountWithinLimit(command.lines.size)
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

            val event = OrderEvent.Created(
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
