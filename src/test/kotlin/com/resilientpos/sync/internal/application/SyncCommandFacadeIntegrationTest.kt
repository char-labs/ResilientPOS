package com.resilientpos.sync.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 배치 항목의 판정(적용·중복·거절·gap·충돌)과 watermark 전진이 실제 `order` Facade와 PostgreSQL까지 이어지는지 본다. Docker가 필요하다.
 * 커밋된 행을 JDBC로 세어 확인한다. 항목 트랜잭션이 하나인지는 Facade 결과가 아니라 롤백 뒤 남은 행으로 본다.
 */
class SyncCommandFacadeIntegrationTest(
    @Autowired private val facade: SyncCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private val terminalId = TerminalId(UUID.randomUUID())

    private fun operation(sequence: Long, order: OrderCommand.Create = OrderFixtures.createOrderCommand(terminalId = terminalId), operationId: UUID = UUID.randomUUID()) =
        SyncCommand.ReceiveBatch.Operation(operationId, sequence, order)

    private fun receive(vararg operations: SyncCommand.ReceiveBatch.Operation) =
        facade.receiveBatch(SyncCommand.ReceiveBatch(terminalId, operations.toList()))

    private fun orderRows(): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from t_order where terminal_id = ?", terminalId.value))

    private fun operationRows(): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from t_sync_operation where terminal_id = ?", terminalId.value))

    private fun storedWatermark(): Long? =
        jdbcTemplate.query("select last_acked_sequence from t_sync_watermark where terminal_id = ?", { rs, _ -> rs.getLong(1) }, terminalId.value).firstOrNull()

    @Test
    fun `순번대로 온 배치는 모두 적용되고 watermark가 마지막 순번이 된다`() {
        val result = receive(operation(1), operation(2), operation(3))

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED, SyncItemStatus.APPLIED), result.items.map { it.status })
        result.items.forEach { assertNotNull(it.orderId) }
        assertEquals(3, result.lastAckedSequence)
        assertEquals(3, storedWatermark())
        assertEquals(3, orderRows())
        assertEquals(3, operationRows())
    }

    @Test
    fun `같은 배치를 다시 보내면 모두 중복이고 같은 orderId를 받으며 행이 늘지 않는다`() {
        val operations = arrayOf(operation(1), operation(2))
        val first = receive(*operations)

        val resent = receive(*operations)

        assertEquals(listOf(SyncItemStatus.DUPLICATE, SyncItemStatus.DUPLICATE), resent.items.map { it.status })
        assertEquals(first.items.map { it.orderId }, resent.items.map { it.orderId })
        assertEquals(2, resent.lastAckedSequence)
        assertEquals(2, orderRows())
        assertEquals(2, operationRows())
    }

    @Test
    fun `확정된 operation에 다른 내용이 오거나 모르는 operation이 확정된 순번으로 오면 충돌이고 아무것도 바뀌지 않는다`() {
        val order = OrderFixtures.createOrderCommand(terminalId = terminalId)
        val applied = operation(1, order)
        receive(applied)
        val changed = order.copy(lines = order.lines.take(1) + order.lines.last().copy(quantity = 9))

        val result = receive(
            operation(1, changed, operationId = applied.operationId),
            operation(1, OrderFixtures.createOrderCommand(terminalId = terminalId)),
        )

        assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.CONFLICT), result.items.map { it.status })
        assertEquals(1, result.lastAckedSequence)
        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
        assertEquals(1, operationRows())
    }

    @Test
    fun `순번이 비면 그 항목은 gap이고 watermark는 멈추며 빈 순번이 오면 이어서 적용된다`() {
        val fourth = operation(4)

        val first = receive(operation(1), operation(2), fourth)

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED, SyncItemStatus.GAP), first.items.map { it.status })
        assertEquals(2, first.lastAckedSequence)
        assertEquals(2, storedWatermark())
        assertEquals(2, orderRows())

        val second = receive(operation(3), fourth)

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED), second.items.map { it.status })
        assertEquals(4, second.lastAckedSequence)
        assertEquals(4, orderRows())
    }

    @Test
    fun `주문이 도메인 규칙으로 거절되면 항목은 거절이고 주문 행 없이 순번만 확정되며 재전송은 같은 거절의 중복이다`() {
        val blankName = OrderFixtures.createOrderCommand(
            terminalId = terminalId,
            lines = listOf(OrderCommand.Create.Line("SKU-1", " ", Money.of("1000", OrderFixtures.krw), 1)),
        )
        val rejected = operation(1, blankName)

        val first = receive(rejected, operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), first.items.map { it.status })
        assertEquals(OrderLine.BLANK_PRODUCT_NAME, first.items[0].errorCode)
        assertNull(first.items[0].orderId)
        assertEquals(2, first.lastAckedSequence)
        assertEquals(1, orderRows())
        assertEquals(2, operationRows())

        val resent = receive(rejected)

        assertEquals(SyncItemStatus.DUPLICATE, resent.items.single().status)
        assertEquals(OrderLine.BLANK_PRODUCT_NAME, resent.items.single().errorCode)
        assertEquals(2, operationRows())
    }

    @Test
    fun `접수 기록 저장이 실패하면 그 항목의 주문 행도 남지 않는다`() {
        // 같은 operationId를 다른 순번·다른 주문으로 두 번 보낸다. 둘째 항목은 주문을 넣은 뒤 접수 기록의 unique에 걸려
        // 커밋에 실패한다. 항목 트랜잭션이 하나라면 그 주문 행도 함께 롤백된다.
        val reused = UUID.randomUUID()
        val secondOrder = OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = ClientOrderId(UUID.randomUUID()))

        assertFailsWith<DataIntegrityViolationException> {
            receive(operation(1, operationId = reused), operation(2, secondOrder, operationId = reused))
        }

        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
        assertEquals(
            0L,
            jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", secondOrder.clientOrderId.value),
        )
    }

    @Test
    fun `배치가 상한을 넘으면 저장소를 건드리기 전에 거절한다`() {
        val operations = Array(SyncCommandFacade.MAX_OPERATIONS + 1) { operation(it + 1L) }

        val failure = assertFailsWith<DomainException> { receive(*operations) }

        assertEquals(SyncCommandFacade.SYNC_BATCH_TOO_LARGE, failure.code)
        assertNull(storedWatermark())
        assertEquals(0, orderRows())
    }
}
