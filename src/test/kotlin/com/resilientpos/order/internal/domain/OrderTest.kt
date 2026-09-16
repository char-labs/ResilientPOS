package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrderTest {

    private val krw = Currency.getInstance("KRW")
    private val usd = Currency.getInstance("USD")
    private val now = Instant.parse("2026-09-16T00:00:00Z")

    private val storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val terminalId = TerminalId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val clientOrderId = ClientOrderId(UUID.fromString("00000000-0000-0000-0000-000000000003"))

    private class FixedIdentifierGenerator(private val ids: List<UUID>) : IdentifierGenerator {
        private var index = 0

        override fun next(): UUID {
            val id = ids[index]
            index += 1
            return id
        }
    }

    private fun orderLine(
        productCode: String = "SKU-1",
        productName: String = "아메리카노",
        unitPrice: Money = Money.of("1000", krw),
        quantity: Int = 1,
    ) = CreateOrderLine(productCode, productName, unitPrice, quantity)

    private fun createOrderCommand(
        currency: Currency = krw,
        lines: List<CreateOrderLine> = listOf(orderLine()),
    ) = CreateOrderCommand(storeId, terminalId, clientOrderId, currency, lines)

    @Test
    fun `유효한 명령으로 OPEN 주문과 OrderCreated를 만든다`() {
        val orderId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        val command = createOrderCommand(
            lines = listOf(
                orderLine(unitPrice = Money.of("1000", krw), quantity = 2),
                orderLine(unitPrice = Money.of("500", krw), quantity = 3),
            ),
        )

        val transition = Order.create(command, FixedIdentifierGenerator(listOf(orderId, eventId)), now)

        val order = transition.aggregate
        assertEquals(OrderStatus.OPEN, order.status)
        assertEquals(orderId, order.id.value)
        assertEquals(command.storeId, order.storeId)
        assertEquals(command.terminalId, order.terminalId)
        assertEquals(command.clientOrderId, order.clientOrderId)
        assertEquals(command.currency, order.currency)
        assertEquals(command.lines.size, order.lines.size)

        assertEquals(1, transition.events.size)
        val event = transition.events.single()
        assertTrue(event is OrderCreated)
        assertEquals(orderId, event.orderId.value)
        assertEquals(eventId, event.eventId)
        assertEquals(now, event.occurredAt)
        assertEquals(command.storeId, event.storeId)
        assertEquals(command.terminalId, event.terminalId)
        assertEquals(command.clientOrderId, event.clientOrderId)
    }

    @Test
    fun `빈 line을 EMPTY_ORDER로 거절한다`() {
        val command = createOrderCommand(lines = emptyList())

        val failure = assertFailsWith<IllegalArgumentException> {
            Order.create(command, FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())), now)
        }

        assertEquals(Order.EMPTY_ORDER, failure.message)
    }

    @Test
    fun `0 이하 수량과 음수 단가를 거절한다`() {
        val invalidQuantity = assertFailsWith<IllegalArgumentException> {
            Order.create(
                createOrderCommand(lines = listOf(orderLine(quantity = 0))),
                FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())),
                now,
            )
        }
        assertEquals(OrderLine.INVALID_QUANTITY, invalidQuantity.message)

        val negativeUnitPrice = assertFailsWith<IllegalArgumentException> {
            Order.create(
                createOrderCommand(lines = listOf(orderLine(unitPrice = Money.of("-1000", krw)))),
                FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())),
                now,
            )
        }
        assertEquals(OrderLine.NEGATIVE_UNIT_PRICE, negativeUnitPrice.message)
    }

    @Test
    fun `주문 통화와 다른 단가를 CURRENCY_MISMATCH로 거절한다`() {
        val command = createOrderCommand(
            currency = krw,
            lines = listOf(orderLine(unitPrice = Money.of("10.00", usd))),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            Order.create(command, FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())), now)
        }

        assertEquals(Order.CURRENCY_MISMATCH, failure.message)
    }

    @Test
    fun `합계는 line 단가 곱 수량의 합이다`() {
        val command = createOrderCommand(
            lines = listOf(
                orderLine(unitPrice = Money.of("1000", krw), quantity = 2),
                orderLine(unitPrice = Money.of("500", krw), quantity = 3),
            ),
        )

        val transition = Order.create(
            command,
            FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())),
            now,
        )

        assertEquals(Money.of("3500", krw), transition.aggregate.total)
    }
}
