package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.CheckoutId
import com.resilientpos.order.ClientOrderId
import com.resilientpos.contract.OrderPaid
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrderMarkPaidTest {

    private val krw = Currency.getInstance("KRW")
    private val now = Instant.parse("2026-09-23T00:00:00Z")
    private val ids = object : IdentifierGenerator {
        override fun next(): UUID = UUID.randomUUID()
    }

    /** 3500원 주문의 checkout 1회차를 요청한 상태. */
    private fun checkoutRequested(): Order {
        val command = OrderCommand.Create(
            StoreId(UUID.randomUUID()),
            TerminalId(UUID.randomUUID()),
            ClientOrderId(UUID.randomUUID()),
            krw,
            listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("3500", krw), 1)),
        )
        return Order.create(command, ids, now).aggregate.requestCheckout(ids, now).aggregate
    }

    private fun assertMismatch(block: () -> Unit) {
        val thrown = assertFailsWith<DomainException>(block = block)
        assertEquals(Order.PAYMENT_APPROVAL_MISMATCH, thrown.code)
        assertEquals(DomainErrorKind.UNPROCESSABLE, thrown.kind)
    }

    @Test
    fun `확정값과 같은 승인이면 PAID가 되고 OrderPaid를 낸다`() {
        val order = checkoutRequested()

        val transition = order.markPaid(1, Money.of("3500", krw), ids, now)

        assertEquals(OrderStatus.PAID, transition.aggregate.status)
        val paid = assertIs<OrderPaid>(transition.events.single())
        assertEquals(order.id.value, paid.orderId)
        assertEquals(order.storeId, paid.storeId)
        assertEquals(order.terminalId, paid.terminalId)
        assertEquals(1, paid.checkoutNo)
        assertEquals(Money.of("3500", krw), paid.amount)
    }

    @Test
    fun `금액이 다르면 전이하지 않고 거절한다`() {
        assertMismatch { checkoutRequested().markPaid(1, Money.of("3400", krw), ids, now) }
    }

    @Test
    fun `통화가 다르면 전이하지 않고 거절한다`() {
        assertMismatch { checkoutRequested().markPaid(1, Money.of("3500", Currency.getInstance("JPY")), ids, now) }
    }

    @Test
    fun `없는 회차의 승인이면 거절한다`() {
        assertMismatch { checkoutRequested().markPaid(2, Money.of("3500", krw), ids, now) }
    }

    @Test
    fun `이전 회차의 승인은 금액이 같아도 거절한다`() {
        val order = checkoutRequested()
        val reopened = Order.restore(
            order.id, order.storeId, order.terminalId, order.clientOrderId, order.currency, order.status, order.lines,
            order.checkouts + Checkout(CheckoutId(UUID.randomUUID()), 2, Money.of("3500", krw)),
        )

        assertMismatch { reopened.markPaid(1, Money.of("3500", krw), ids, now) }
    }

    @Test
    fun `checkout을 요청하지 않은 주문의 승인이면 거절한다`() {
        val open = Order.create(
            OrderCommand.Create(
                StoreId(UUID.randomUUID()), TerminalId(UUID.randomUUID()), ClientOrderId(UUID.randomUUID()), krw,
                listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("3500", krw), 1)),
            ),
            ids,
            now,
        ).aggregate

        assertMismatch { open.markPaid(1, Money.of("3500", krw), ids, now) }
    }

    @Test
    fun `PAID 주문에 다른 회차의 승인이 오면 거절한다`() {
        val paid = checkoutRequested().markPaid(1, Money.of("3500", krw), ids, now).aggregate

        assertMismatch { paid.markPaid(2, Money.of("3500", krw), ids, now) }
    }

    @Test
    fun `PAID 주문에 checkout을 다시 요청하면 지금 상태를 돌려준다`() {
        val paid = checkoutRequested().markPaid(1, Money.of("3500", krw), ids, now).aggregate

        val again = paid.requestCheckout(ids, now)

        assertEquals(OrderStatus.PAID, again.aggregate.status)
        assertTrue(again.events.isEmpty())
    }

    @Test
    fun `PAID 주문에 같은 승인이 다시 오면 아무것도 하지 않는다`() {
        val paid = checkoutRequested().markPaid(1, Money.of("3500", krw), ids, now).aggregate

        val again = paid.markPaid(1, Money.of("3500", krw), ids, now)

        assertEquals(OrderStatus.PAID, again.aggregate.status)
        assertTrue(again.events.isEmpty())
    }
}
