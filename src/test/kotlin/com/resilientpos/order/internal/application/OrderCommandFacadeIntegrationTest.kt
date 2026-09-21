package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate

@RecordApplicationEvents
class OrderCommandFacadeIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val repository: OrderRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transaction: TransactionTemplate,
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
    fun `신규 저장은 INSERT 한 번이고 생성 시각과 수정 시각이 채워지며 삭제 시각은 비어 있다`() {
        val result = facade.create(OrderFixtures.createOrderCommand())

        val row = jdbcTemplate.queryForMap(
            "select created_at, updated_at, deleted_at, version from t_order where id = ?",
            result.orderId.value,
        )

        // 두 시각은 Hibernate가 JVM에서 따로 만든다. 같다거나 순서가 있다고 보장하지 않는다. 갱신된 적 없는 행은 version 0으로 판정한다.
        assertNotNull(row["created_at"])
        assertNotNull(row["updated_at"])
        assertNull(row["deleted_at"])
        assertEquals(0L, row["version"], "신규 저장은 INSERT 한 번이다. merge로 빠지면 UPDATE가 따라붙어 version이 1이 된다")
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
        val stored = assertNotNull(repository.findById(first.orderId))
        assertEquals(command.lines.map { it.unitPrice }, stored.lines.map { it.unitPrice })
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

        assertFailsWith<DomainException> { facade.create(command) }

        assertEquals(0L, orderRowCount(terminalId, clientOrderId))
    }

    private fun Order.withLines(lines: List<OrderLine>): Order =
        Order.restore(id, storeId, terminalId, clientOrderId, currency, status, lines, checkouts)

    @Test
    fun `line이 바뀐 주문을 다시 저장하면 같은 ID는 갱신하고 새 ID는 추가하고 없어진 ID는 soft delete한다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val stored = assertNotNull(repository.findById(orderId))
        val added = OrderLine(OrderLineId(UUID.randomUUID()), "SKU-3", "바닐라라떼", Money.of("700", OrderFixtures.krw), 1)
        val grown = listOf(stored.lines[0].copy(quantity = 5), stored.lines[1], added)

        transaction.executeWithoutResult { repository.save(stored.withLines(grown)) }
        assertEquals(grown, repository.findById(orderId)?.lines)

        val middleRemoved = listOf(grown[0], grown[2])
        transaction.executeWithoutResult { repository.save(stored.withLines(middleRemoved)) }
        assertEquals(middleRemoved, repository.findById(orderId)?.lines)
        assertEquals(
            listOf(grown[1].id.value),
            jdbcTemplate.queryForList("select id from t_order_line where order_id = ? and deleted_at is not null", UUID::class.java, orderId.value),
        )
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

    @Test
    fun `같은 키에 수량이 다른 command는 IDEMPOTENCY_CONFLICT를 던지고 기존 행과 line을 바꾸지 않는다`(events: ApplicationEvents) {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = clientOrderId)

        val first = facade.create(command)
        val conflicting = command.copy(lines = command.lines.map { it.copy(quantity = it.quantity + 1) })

        val failure = assertFailsWith<DomainException> { facade.create(conflicting) }

        assertEquals(Order.IDEMPOTENCY_CONFLICT, failure.code)
        assertEquals(DomainErrorKind.CONFLICT, failure.kind)
        assertEquals(1L, orderRowCount(terminalId, clientOrderId))
        val stored = assertNotNull(repository.findById(first.orderId))
        assertEquals(command.lines.map { it.quantity }, stored.lines.map { it.quantity })
        assertEquals(
            1L,
            events.stream(OrderCreated::class.java).filter { it.orderId == first.orderId }.count(),
        )
    }

    @Test
    fun `같은 키에 line 순서만 바꾼 command는 기존 결과를 돌려준다`() {
        val terminalId = TerminalId(UUID.randomUUID())
        val clientOrderId = ClientOrderId(UUID.randomUUID())
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = clientOrderId)
        val reordered = command.copy(lines = command.lines.reversed())

        val first = facade.create(command)
        val second = facade.create(reordered)

        assertEquals(first.orderId, second.orderId)
        assertEquals(1L, orderRowCount(terminalId, clientOrderId))
    }
}
