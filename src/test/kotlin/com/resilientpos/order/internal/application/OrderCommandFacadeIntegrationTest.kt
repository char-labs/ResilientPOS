package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents

@RecordApplicationEvents
class OrderCommandFacadeIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val repository: OrderRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private fun orderRowCount(terminalId: TerminalId, clientOrderId: ClientOrderId): Long =
        jdbcTemplate.queryForObject(
            "select count(*) from t_order where terminal_id = ? and client_order_id = ?",
            Long::class.java,
            terminalId.value,
            clientOrderId.value,
        ) ?: 0L

    private fun orderLineRowCount(orderId: UUID): Long =
        jdbcTemplate.queryForObject(
            "select count(*) from t_order_line where order_id = ?",
            Long::class.java,
            orderId,
        ) ?: 0L

    @Test
    fun `주문을 저장하고 OPEN 결과를 돌려준다`() {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = clientOrderId)

        val result = facade.create(command)

        assertEquals(OrderStatus.OPEN, result.status)
        assertEquals(1L, orderRowCount(terminalId, clientOrderId))
        assertEquals(command.lines.size.toLong(), orderLineRowCount(result.orderId.value))

        val stored = repository.findById(result.orderId)
        assertTrue(stored != null)
        assertEquals(command.lines.size, stored.lines.size)
        assertEquals(command.currency, stored.currency)
        assertEquals(command.lines.fold(Money.zero(command.currency)) { acc, line -> acc + (line.unitPrice * line.quantity) }, stored.total)
    }

    @Test
    fun `같은 clientOrderId 재시도는 기존 결과를 돌려주고 행을 늘리지 않는다`() {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = clientOrderId)

        val first = facade.create(command)
        val second = facade.create(command)

        assertEquals(first.orderId, second.orderId)
        assertEquals(first.status, second.status)
        assertEquals(1L, orderRowCount(terminalId, clientOrderId))
    }

    @Test
    fun `OrderCreated는 첫 호출에만 한 번 발행된다`(events: ApplicationEvents) {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = clientOrderId)

        val first = facade.create(command)
        assertEquals(
            1L,
            events.stream(OrderCreated::class.java).filter { it.orderId == first.orderId }.count(),
        )

        facade.create(command)
        assertEquals(
            1L,
            events.stream(OrderCreated::class.java).filter { it.orderId == first.orderId }.count(),
        )
    }

    @Test
    fun `도메인이 거절한 명령은 행을 남기지 않는다`() {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(
            terminalId = terminalId,
            clientOrderId = clientOrderId,
            lines = emptyList<CreateOrderLine>(),
        )

        assertFailsWith<IllegalArgumentException> { facade.create(command) }

        assertEquals(0L, orderRowCount(terminalId, clientOrderId))
    }

    @Test
    fun `같은 terminal_id와 client_order_id는 DB unique가 거절한다`() {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = clientOrderId)

        facade.create(command)

        val duplicate = Order.create(
            command,
            UuidV7IdentifierGenerator(),
            Clock.systemUTC().instant(),
        ).aggregate

        assertFailsWith<DataIntegrityViolationException> { repository.save(duplicate) }
    }
}
