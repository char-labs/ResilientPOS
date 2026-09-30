package com.resilientpos.sync.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainException
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    @Autowired private val orders: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private val terminalId = TerminalId(UUID.randomUUID())

    private fun payload(command: OrderCommand.Create = OrderFixtures.createOrderCommand(terminalId = terminalId)) = OrderPayload(
        storeId = command.storeId,
        clientOrderId = command.clientOrderId,
        currency = command.currency,
        lines = command.lines.map { OrderPayload.Line(it.productCode, it.productName, it.unitPrice.amount, it.quantity) },
    )

    private fun operation(sequence: Long, order: OrderPayload = payload(), operationId: UUID = UUID.randomUUID()) =
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
        val order = payload()
        val applied = operation(1, order)
        receive(applied)
        val changed = order.copy(lines = order.lines.take(1) + order.lines.last().copy(quantity = 9))

        val result = receive(
            operation(1, changed, operationId = applied.operationId),
            operation(1, payload()),
        )

        assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.CONFLICT), result.items.map { it.status })
        assertEquals(1, result.lastAckedSequence)
        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
        assertEquals(1, operationRows())
    }

    @Test
    fun `이미 기록된 operation이 다른 순번으로 오면 확정된 순번이든 다음 순번이든 충돌이고 아무것도 바뀌지 않는다`() {
        val order = payload()
        val applied = operation(1, order)
        receive(applied)

        val result = receive(
            operation(2, order, operationId = applied.operationId),
            operation(2, payload()),
        )

        // 첫 항목은 다음 순번(2)인데 operationId가 이미 기록돼 CONFLICT다. 둘째 항목은 순번 2가 아직 비어 있으므로 APPLIED다.
        assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(2, result.lastAckedSequence)
        assertEquals(2, orderRows())

        // 같은 내용이라도 원래(1)와 다른 확정된 순번으로 오면 DUPLICATE가 아니라 CONFLICT다.
        val acked = receive(operation(2, order, operationId = applied.operationId))

        assertEquals(SyncItemStatus.CONFLICT, acked.items.single().status)
        assertEquals(2, storedWatermark())
        assertEquals(2, operationRows())
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
        assertEquals(4, storedWatermark())
        assertEquals(4, orderRows())
    }

    @Test
    fun `금액 규칙에 어긋나는 항목은 배치 오류가 아니라 거절이고 다음 항목은 적용된다`() {
        // KRW는 소수 자릿수가 0이라 4500.5는 Money가 INVALID_MONEY_SCALE로 거절한다. 이 거절이 Controller에서 나면 배치 전체가
        // 400이 되어 단말이 같은 봉투를 영원히 다시 보낸다. 항목 안에서 나야 REJECTED로 확정되고 단말이 버린다.
        val badScale = payload().let { it.copy(lines = listOf(it.lines.first().copy(unitPrice = BigDecimal("4500.5")))) }

        val result = receive(operation(1, badScale), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals("INVALID_MONEY_SCALE", result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `같은 clientOrderId의 주문이 다른 내용으로 이미 있으면 항목은 IDEMPOTENCY_CONFLICT 거절이고 순번은 확정된다`() {
        // 기존 주문 조회 뒤에 나는 거절이라 첫 트랜잭션이 읽은 것을 롤백하는 경로를 지난다.
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId)
        orders.create(command)
        val differentContent = payload(command).let { it.copy(lines = listOf(it.lines.first().copy(quantity = 7))) }

        val result = receive(operation(1, differentContent))

        assertEquals(SyncItemStatus.REJECTED, result.items.single().status)
        assertEquals(Order.IDEMPOTENCY_CONFLICT, result.items.single().errorCode)
        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `같은 단말의 같은 배치가 동시에 오면 항목 단위로 직렬화되어 주문과 기록이 항목 수만큼만 생긴다`() {
        val batch = arrayOf(operation(1), operation(2), operation(3))
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = List(2) { executor.submit<SyncCommand.ReceiveBatch.Result> { receive(*batch) } }.map { it.get(30, TimeUnit.SECONDS) }

            results.flatMap { it.items }.forEach { assert(it.status == SyncItemStatus.APPLIED || it.status == SyncItemStatus.DUPLICATE) { it.toString() } }
            assertEquals(3, storedWatermark())
            assertEquals(3, orderRows())
            assertEquals(3, operationRows())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `주문이 도메인 규칙으로 거절되면 항목은 거절이고 주문 행 없이 순번만 확정되며 재전송은 같은 거절의 중복이다`() {
        val blankName = payload().let { it.copy(lines = listOf(OrderPayload.Line("SKU-1", " ", BigDecimal("1000"), 1))) }
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
        // 순번 2의 접수 기록을 잠금 밖에서 미리 넣어 둔다(수동 수정을 흉내 낸다). 둘째 항목은 주문을 넣은 뒤 접수 기록의
        // unique (terminal_id, sequence)에 걸려 커밋에 실패한다. 항목 트랜잭션이 하나라면 그 주문 행도 함께 롤백된다.
        jdbcTemplate.update(
            "insert into t_sync_operation (id, terminal_id, operation_id, sequence, payload_hash, status, created_at, updated_at) " +
                "values (?, ?, ?, 2, 'stray', 'APPLIED', now(), now())",
            UUID.randomUUID(), terminalId.value, UUID.randomUUID(),
        )
        val secondOrder = payload(OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = ClientOrderId(UUID.randomUUID())))

        assertFailsWith<DataIntegrityViolationException> {
            receive(operation(1), operation(2, secondOrder))
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
