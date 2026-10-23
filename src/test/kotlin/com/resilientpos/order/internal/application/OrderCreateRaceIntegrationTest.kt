package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import jakarta.persistence.EntityManager
import java.sql.SQLException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionTemplate

/**
 * 같은 `(terminalId, clientOrderId)`의 주문 생성이 커밋되기 전에 `OrderCommandFacade.create`가 겹치면 `DomainException`이 아닌
 * 경합 예외(`ConcurrencyFailureException`)로 끝나고, 앞 주문이 커밋된 뒤 다시 요청하면 앞 주문을 받는지 고정한다. Docker가 필요하다.
 * 겹침은 앞 트랜잭션을 커밋 전에 붙들어 두고, 뒤 INSERT가 그 트랜잭션에 막혀 기다리는 것을 `pg_blocking_pids`로 확인해 만든다.
 */
class OrderCreateRaceIntegrationTest(
    private val orders: OrderCommandFacade,
    private val jdbcTemplate: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val entityManager: EntityManager,
) : PostgresIntegrationTest() {

    private val command = OrderFixtures.createOrderCommand()

    private fun orderRows(): Long =
        checkNotNull(
            jdbcTemplate.queryForObject<Long>(
                "select count(*) from t_order where terminal_id = ? and client_order_id = ?",
                command.terminalId.value,
                command.clientOrderId.value,
            ),
        )

    /**
     * [holder] backend에 막혀 아직 얻지 못한 잠금 수. 다른 테스트나 리스너의 잠금 대기는 세지 않는다.
     * `pg_stat_activity`는 트랜잭션 안에서 처음 읽은 시점의 세션 목록에 고정되므로 매번 새로 읽는 `pg_locks`를 본다.
     */
    private fun locksBlockedBy(holder: Int): Long =
        jdbcTemplate.queryForObject<Long>("select count(*) from pg_locks where not granted and ? = any(pg_blocking_pids(pid))", holder) ?: 0L

    @Test
    fun `커밋 전인 같은 키 생성과 겹친 create는 경합 예외로 끝나고 다시 요청하면 앞 주문을 받는다`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val (orderId, overlapping) = checkNotNull(
                transaction.execute {
                    val orderId = orders.createInCallerTransaction(command).orderId
                    // 어댑터는 flush하지 않는다. 이 flush는 INSERT를 커밋 전에 내보내 겹침을 만드는 장치다.
                    entityManager.flush()
                    // JpaTransactionManager가 DataSource에 이 트랜잭션의 연결을 묶어 두므로 JdbcTemplate도 같은 backend를 쓴다.
                    val holder = checkNotNull(jdbcTemplate.queryForObject<Int>("select pg_backend_pid()"))
                    val overlapping = executor.submit<Any> { orders.create(command) }
                    awaitUntil("create가 앞 생성의 unique 키 잠금을 기다린다") { locksBlockedBy(holder) != 0L }
                    assertFalse(overlapping.isDone, "앞 트랜잭션이 커밋하기 전에는 끝나지 않아야 한다")
                    orderId to overlapping
                },
            )

            val failure = assertFailsWith<ExecutionException> { overlapping.get(10, TimeUnit.SECONDS) }
            val cause = assertIs<ConcurrencyFailureException>(failure.cause)
            val violation = assertIs<SQLException>(cause.mostSpecificCause)
            assertEquals("23505", violation.sqlState, "잠금 대기 초과가 아니라 unique 위반이다")
            // DB에 실제로 붙은 이름을 고정하려고 운영 코드의 상수 대신 리터럴로 든다. 이름을 바꾸면 상수와 함께 고친다.
            assertTrue(violation.message.orEmpty().contains("t_order_terminal_id_client_order_id_key"))
            val message = cause.message.orEmpty()
            assertTrue(message.contains(command.terminalId.value.toString()), message)
            assertTrue(message.contains(command.clientOrderId.value.toString()), message)
            assertEquals(1L, orderRows())

            assertEquals(orderId, orders.create(command).orderId)
            assertEquals(1L, orderRows())
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
