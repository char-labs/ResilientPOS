package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.order.CheckoutId
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderPaymentRequested
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrderTest {

    private val krw = Currency.getInstance("KRW")
    private val usd = Currency.getInstance("USD")
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val checkoutId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val blankValues = listOf("", "   ", "\t", "\n", "\u00A0")

    private val storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val terminalId = TerminalId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val clientOrderId = ClientOrderId(UUID.fromString("00000000-0000-0000-0000-000000000003"))

    private class FixedIdentifierGenerator(private val ids: List<UUID>) : IdentifierGenerator {
        private var index = 0

        override fun next(): UUID {
            val id = ids.getOrNull(index) ?: UUID.randomUUID()
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
        val lineIds = listOf(UUID.fromString("00000000-0000-0000-0000-0000000000b1"), UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
        val command = createOrderCommand(
            lines = listOf(
                orderLine(unitPrice = Money.of("1000", krw), quantity = 2),
                orderLine(unitPrice = Money.of("500", krw), quantity = 3),
            ),
        )

        val transition = Order.create(command, FixedIdentifierGenerator(listOf(orderId) + lineIds + eventId), now)

        val order = transition.aggregate
        assertEquals(OrderStatus.OPEN, order.status)
        assertEquals(orderId, order.id.value)
        assertEquals(command.storeId, order.storeId)
        assertEquals(command.terminalId, order.terminalId)
        assertEquals(command.clientOrderId, order.clientOrderId)
        assertEquals(command.currency, order.currency)
        assertEquals(command.lines.size, order.lines.size)
        assertEquals(lineIds, order.lines.map { it.id.value })

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

        val failure = assertFailsWith<DomainException> {
            Order.create(command, FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())), now)
        }

        assertEquals(Order.EMPTY_ORDER, failure.code)
        assertTrue(failure.message.isNotBlank())
        assertTrue(failure.message != failure.code)
    }

    @Test
    fun `0 이하 수량과 음수 단가를 거절한다`() {
        val invalidQuantity = assertFailsWith<DomainException> {
            Order.create(
                createOrderCommand(lines = listOf(orderLine(quantity = 0))),
                FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())),
                now,
            )
        }
        assertEquals(OrderLine.INVALID_QUANTITY, invalidQuantity.code)
        assertTrue(invalidQuantity.message.isNotBlank())
        assertTrue(invalidQuantity.message != invalidQuantity.code)

        val negativeUnitPrice = assertFailsWith<DomainException> {
            Order.create(
                createOrderCommand(lines = listOf(orderLine(unitPrice = Money.of("-1000", krw)))),
                FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())),
                now,
            )
        }
        assertEquals(OrderLine.NEGATIVE_UNIT_PRICE, negativeUnitPrice.code)
        assertTrue(negativeUnitPrice.message.isNotBlank())
        assertTrue(negativeUnitPrice.message != negativeUnitPrice.code)
    }

    @Test
    fun `빈 productCode를 BLANK_PRODUCT_CODE로 거절한다`() {
        blankValues.forEach { blank ->
            val failure = assertFailsWith<DomainException>("productCode='$blank'") {
                Order.create(
                    createOrderCommand(lines = listOf(orderLine(productCode = blank))),
                    FixedIdentifierGenerator(emptyList()),
                    now,
                )
            }

            assertEquals(OrderLine.BLANK_PRODUCT_CODE, failure.code)
            assertEquals(DomainErrorKind.INVALID, failure.kind)
            assertTrue(failure.message.isNotBlank())
            assertTrue(failure.message != failure.code)
        }
    }

    @Test
    fun `빈 productName을 BLANK_PRODUCT_NAME으로 거절한다`() {
        blankValues.forEach { blank ->
            val failure = assertFailsWith<DomainException>("productName='$blank'") {
                Order.create(
                    createOrderCommand(lines = listOf(orderLine(productName = blank))),
                    FixedIdentifierGenerator(emptyList()),
                    now,
                )
            }

            assertEquals(OrderLine.BLANK_PRODUCT_NAME, failure.code)
            assertEquals(DomainErrorKind.INVALID, failure.kind)
            assertTrue(failure.message.isNotBlank())
            assertTrue(failure.message != failure.code)
        }
    }

    @Test
    fun `앞뒤 공백이 있는 상품 코드와 상품명은 다듬지 않고 받는다`() {
        val command = createOrderCommand(lines = listOf(orderLine(productCode = " SKU-1 ", productName = " 아메리카노 ")))

        val line = Order.create(command, FixedIdentifierGenerator(emptyList()), now).aggregate.lines.single()

        assertEquals(" SKU-1 ", line.productCode)
        assertEquals(" 아메리카노 ", line.productName)
    }

    @Test
    fun `주문 통화와 다른 단가를 CURRENCY_MISMATCH로 거절한다`() {
        val command = createOrderCommand(
            currency = krw,
            lines = listOf(orderLine(unitPrice = Money.of("10.00", usd))),
        )

        val failure = assertFailsWith<DomainException> {
            Order.create(command, FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())), now)
        }

        assertEquals(Order.CURRENCY_MISMATCH, failure.code)
        assertTrue(failure.message.isNotBlank())
        assertTrue(failure.message != failure.code)
    }

    @Test
    fun `합계가 저장 범위를 넘으면 주문을 만들지 않는다`() {
        val command = createOrderCommand(
            lines = listOf(orderLine(unitPrice = Money.of("999999999999999", krw), quantity = 2)),
        )

        val failure = assertFailsWith<DomainException> {
            Order.create(command, FixedIdentifierGenerator(emptyList()), now)
        }

        assertEquals(Money.INVALID_MONEY_AMOUNT, failure.code)
        assertEquals(DomainErrorKind.INVALID, failure.kind)
    }

    @Test
    fun `여러 line의 합산도 저장 범위를 기준으로 판정한다`() {
        val half = Money.of("500000000000000", krw)
        val justUnder = Money.of("999999999999998", krw)

        assertFailsWith<DomainException>("합이 16자리") {
            Order.create(
                createOrderCommand(lines = listOf(orderLine(unitPrice = half), orderLine(productCode = "SKU-2", unitPrice = half))),
                FixedIdentifierGenerator(emptyList()),
                now,
            )
        }

        val ok = Order.create(
            createOrderCommand(lines = listOf(orderLine(unitPrice = justUnder), orderLine(productCode = "SKU-2", unitPrice = Money.of("1", krw)))),
            FixedIdentifierGenerator(emptyList()),
            now,
        )
        assertEquals(Money.of("999999999999999", krw), ok.aggregate.total)
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

    @Test
    fun `verifySamePayload는 같은 command와 line 순서·금액 표기만 다른 command에 던지지 않는다`() {
        val lineA = orderLine(productCode = "SKU-1", unitPrice = Money.of("1000", krw), quantity = 2)
        val lineB = orderLine(productCode = "SKU-2", unitPrice = Money.of("500", krw), quantity = 3)
        val command = createOrderCommand(lines = listOf(lineA, lineB))
        val reordered = createOrderCommand(lines = listOf(lineB, lineA))
        val order = Order.create(command, FixedIdentifierGenerator(emptyList()), now).aggregate

        order.verifySamePayload(command)
        order.verifySamePayload(reordered)

        val usdCommand = createOrderCommand(currency = usd, lines = listOf(orderLine(unitPrice = Money.of("45", usd))))
        val usdCommandFormatted = createOrderCommand(currency = usd, lines = listOf(orderLine(unitPrice = Money.of("45.00", usd))))
        val usdOrder = Order.create(usdCommand, FixedIdentifierGenerator(emptyList()), now).aggregate

        usdOrder.verifySamePayload(usdCommandFormatted)
    }

    @Test
    fun `verifySamePayload는 필드가 다르면 IDEMPOTENCY_CONFLICT를 던지고 같으면 던지지 않는다`() {
        val baseCommand = createOrderCommand(lines = listOf(orderLine(unitPrice = Money.of("1000", krw), quantity = 2)))
        val order = Order.create(baseCommand, FixedIdentifierGenerator(emptyList()), now).aggregate

        val variants = listOf(
            "storeId" to baseCommand.copy(storeId = StoreId(UUID.randomUUID())),
            "currency" to baseCommand.copy(
                currency = usd,
                lines = listOf(orderLine(unitPrice = Money.of("10.00", usd), quantity = 2)),
            ),
            "productCode" to baseCommand.copy(lines = listOf(orderLine(productCode = "SKU-9", unitPrice = Money.of("1000", krw), quantity = 2))),
            "productName" to baseCommand.copy(lines = listOf(orderLine(productName = "카페모카", unitPrice = Money.of("1000", krw), quantity = 2))),
            "unitPrice" to baseCommand.copy(lines = listOf(orderLine(unitPrice = Money.of("2000", krw), quantity = 2))),
            "quantity" to baseCommand.copy(lines = listOf(orderLine(unitPrice = Money.of("1000", krw), quantity = 5))),
            "line 개수" to baseCommand.copy(
                lines = baseCommand.lines + orderLine(productCode = "SKU-2", unitPrice = Money.of("500", krw), quantity = 1),
            ),
            "같은 line 중복" to baseCommand.copy(lines = baseCommand.lines + baseCommand.lines.first()),
        )

        variants.forEach { (label, variant) ->
            val failure = assertFailsWith<DomainException>(label) { order.verifySamePayload(variant) }
            assertEquals(Order.IDEMPOTENCY_CONFLICT, failure.code, label)
            assertEquals(DomainErrorKind.CONFLICT, failure.kind, label)
        }

        order.verifySamePayload(baseCommand)
    }

    @Test
    fun `OPEN 주문의 requestCheckout은 CHECKOUT_REQUESTED와 1회차 Checkout을 가진 새 주문을 돌려주고 원본은 그대로다`() {
        val command = createOrderCommand(
            lines = listOf(
                orderLine(unitPrice = Money.of("1000", krw), quantity = 2),
                orderLine(productCode = "SKU-2", unitPrice = Money.of("500", krw), quantity = 3),
            ),
        )
        val order = Order.create(command, FixedIdentifierGenerator(emptyList()), now).aggregate

        val requested = order.requestCheckout(FixedIdentifierGenerator(listOf(checkoutId)), now).aggregate

        assertEquals(OrderStatus.CHECKOUT_REQUESTED, requested.status)
        assertEquals(listOf(Checkout(CheckoutId(checkoutId), 1, Money.of("3500", krw))), requested.checkouts)
        assertEquals(order.id, requested.id)
        assertEquals(order.storeId, requested.storeId)
        assertEquals(order.terminalId, requested.terminalId)
        assertEquals(order.clientOrderId, requested.clientOrderId)
        assertEquals(order.currency, requested.currency)
        assertEquals(order.lines, requested.lines)

        assertEquals(OrderStatus.OPEN, order.status)
        assertEquals(emptyList(), order.checkouts)
    }

    @Test
    fun `requestCheckout은 확정 금액을 담은 OrderPaymentRequested 한 건을 만든다`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val requestedAt = Instant.parse("2026-09-21T03:00:00Z")
        val command = createOrderCommand(lines = listOf(orderLine(unitPrice = Money.of("1000", krw), quantity = 2)))
        val order = Order.create(command, FixedIdentifierGenerator(emptyList()), now).aggregate

        val events = order.requestCheckout(FixedIdentifierGenerator(listOf(checkoutId, eventId)), requestedAt).events

        assertEquals(
            listOf(
                OrderPaymentRequested(
                    eventId = eventId,
                    occurredAt = requestedAt,
                    orderId = order.id,
                    storeId = storeId,
                    terminalId = terminalId,
                    checkoutNo = 1,
                    amount = Money.of("2000", krw),
                ),
            ),
            events,
        )
    }

    @Test
    fun `합계가 0인 주문도 0원 Checkout으로 전이한다`() {
        val command = createOrderCommand(lines = listOf(orderLine(unitPrice = Money.zero(krw), quantity = 1)))
        val order = Order.create(command, FixedIdentifierGenerator(emptyList()), now).aggregate

        val transition = order.requestCheckout(FixedIdentifierGenerator(listOf(checkoutId)), now)

        assertEquals(OrderStatus.CHECKOUT_REQUESTED, transition.aggregate.status)
        assertEquals(listOf(Checkout(CheckoutId(checkoutId), 1, Money.zero(krw))), transition.aggregate.checkouts)
        assertEquals(Money.zero(krw), (transition.events.single() as OrderPaymentRequested).amount)
    }

    @Test
    fun `소수 자리가 있는 통화도 확정 금액이 통화 자릿수를 지킨다`() {
        val command = createOrderCommand(currency = usd, lines = listOf(orderLine(unitPrice = Money.of("10.10", usd), quantity = 3)))
        val order = Order.create(command, FixedIdentifierGenerator(emptyList()), now).aggregate

        val transition = order.requestCheckout(FixedIdentifierGenerator(listOf(checkoutId)), now)

        assertEquals(listOf(Checkout(CheckoutId(checkoutId), 1, Money.of("30.30", usd))), transition.aggregate.checkouts)
        assertEquals(Money.of("30.30", usd), (transition.events.single() as OrderPaymentRequested).amount)
    }

    @Test
    fun `이미 CHECKOUT_REQUESTED인 주문의 requestCheckout은 같은 주문과 빈 이벤트를 돌려준다`() {
        val order = Order.create(createOrderCommand(), FixedIdentifierGenerator(emptyList()), now).aggregate
        val requested = order.requestCheckout(FixedIdentifierGenerator(emptyList()), now).aggregate

        val again = requested.requestCheckout(FixedIdentifierGenerator(emptyList()), now)

        assertSame(requested, again.aggregate)
        assertEquals(OrderStatus.CHECKOUT_REQUESTED, again.aggregate.status)
        assertEquals(requested.checkouts, again.aggregate.checkouts)
        assertEquals(emptyList(), again.events)
    }
}
