package com.resilientpos.sync.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.platform.isUniqueViolationOf
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionTemplate

/**
 * 같은 `(terminalId, clientOrderId)`의 주문 생성이 커밋되기 전에 sync `CREATE_ORDER`가 겹치면 배치가 `DomainException`이 아닌
 * 경합 예외(`ConcurrencyFailureException`)로 끝나 항목이 `REJECTED`로 기록되지 않고, 앞 생성이 커밋된 뒤 재전송하면 `APPLIED`가 되는지 고정한다. Docker가 필요하다.
 * 겹침은 앞 트랜잭션을 커밋 전에 붙들어 두고, sync INSERT가 그 트랜잭션에 막혀 기다리는 것을 `pg_blocking_pids`로 확인해 만든다.
 */
class SyncCreateOrderRaceIntegrationTest(
    @Autowired private val facade: SyncCommandFacade,
    @Autowired private val orders: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transaction: TransactionTemplate,
) : PostgresIntegrationTest() {

    private val terminalId = TerminalId(UUID.randomUUID())
    private val operationId = UUID.randomUUID()
    private val command = OrderFixtures.createOrderCommand(terminalId = terminalId)

    private fun batch() = SyncCommand.ReceiveBatch(
        terminalId,
        OrderFixtures.storeId,
        listOf(
            SyncCommand.ReceiveBatch.Operation(
                operationId,
                1,
                OrderPayload(
                    storeId = command.storeId,
                    clientOrderId = command.clientOrderId,
                    currency = command.currency.currencyCode,
                    lines = command.lines.map { OrderPayload.Line(it.productCode, it.productName, it.unitPrice.amount, it.quantity.toBigDecimal()) },
                ),
            ),
        ),
    )

    private fun count(table: String): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from $table where terminal_id = ?", terminalId.value))

    private fun storedWatermark(): Long =
        jdbcTemplate.query("select last_acked_sequence from t_sync_watermark where terminal_id = ?", { rs, _ -> rs.getLong(1) }, terminalId.value).firstOrNull() ?: 0L

    /** [holder] backend에 막혀 기다리는 세션 수. 다른 테스트나 리스너의 잠금 대기는 세지 않는다. */
    private fun sessionsBlockedBy(holder: Int): Long =
        jdbcTemplate.queryForObject<Long>("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))", holder) ?: 0L

    @Test
    fun `커밋 전인 같은 키 생성과 겹친 sync 생성은 경합 예외로 끝나 기록을 남기지 않고 재전송하면 앞 주문으로 APPLIED다`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val (orderId, syncing) = checkNotNull(
                transaction.execute {
                    val orderId = orders.createInCallerTransaction(command).orderId
                    val holder = checkNotNull(jdbcTemplate.queryForObject<Int>("select pg_backend_pid()"))
                    val syncing = executor.submit<SyncCommand.ReceiveBatch.Result> { facade.receiveBatch(batch()) }
                    awaitUntil("sync 생성이 앞 생성의 unique 키 잠금을 기다린다") { sessionsBlockedBy(holder) != 0L }
                    assertFalse(syncing.isDone, "앞 트랜잭션이 커밋하기 전에는 끝나지 않아야 한다")
                    orderId to syncing
                },
            )

            val failure = assertFailsWith<ExecutionException> { syncing.get(10, TimeUnit.SECONDS) }
            val cause = assertIs<ConcurrencyFailureException>(failure.cause)
            assertTrue(cause.isUniqueViolationOf("t_order_terminal_id_client_order_id_key"), "잠금 대기 초과가 아니라 그 제약의 unique 위반이다")
            assertEquals(0, count("t_sync_operation"), "REJECTED 등 접수 기록이 남지 않는다")
            assertEquals(0L, storedWatermark(), "watermark가 전진하지 않는다")
            assertEquals(1, count("t_order"))

            val resent = facade.receiveBatch(batch()).items.single()

            assertEquals(SyncItemStatus.APPLIED, resent.status)
            assertEquals(orderId, resent.orderId)
            assertEquals(1, count("t_order"))
            assertEquals(1, count("t_sync_operation"))
            assertEquals(1L, storedWatermark())
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
