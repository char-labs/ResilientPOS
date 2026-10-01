package com.resilientpos.sync.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.sync.internal.domain.CheckoutPayload
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.EntityManagerFactory
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * sync 항목 하나가 내는 JDBC 문장 수를 Hibernate 통계로 고정한다. Docker가 필요하다.
 * 이미 요청된 주문의 checkout 요청은 바뀐 것이 없으므로 주문 저장(자식 SELECT 둘)을 건너뛴다. 그 회귀를 잡는다.
 * 결제 요청이 커밋되면 발행 기록을 처리하는 비동기 스레드가 같은 SessionFactory로 문장을 내므로, 세기 전에 미완료 발행 기록이 없어질 때까지 기다린다.
 * 통계는 SessionFactory 전체의 누적값이라 테스트 병렬 실행을 켜면 이 측정은 격리가 필요하다(OrderInsertStatementCountIntegrationTest와 같다).
 */
class SyncStatementCountIntegrationTest(
    @Autowired private val facade: SyncCommandFacade,
    @Autowired private val orders: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val statistics: Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private var statisticsWereEnabled = false

    private val terminalId = TerminalId(UUID.randomUUID())

    @BeforeTest
    fun enableStatistics() {
        statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun restoreStatistics() {
        statistics.isStatisticsEnabled = statisticsWereEnabled
    }

    /** 다른 테스트가 남긴 미완료 기록과 섞이지 않게 이 주문의 발행 기록만 본다. 기록이 생기기 전에는 0이라 통과하므로 주문 생성 직후에도 부른다. */
    private fun awaitPublicationsDone(clientOrderId: ClientOrderId) {
        val orderId = jdbcTemplate.queryForObject<UUID>("select id from t_order where client_order_id = ?", clientOrderId.value)
        awaitUntil("이 주문의 미완료 발행 기록이 없다") {
            jdbcTemplate.queryForObject<Long>(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?", "%$orderId%",
            ) == 0L
        }
    }

    /**
     * [operation]을 담은 배치를 [ATTEMPTS]번 받고 그중 가장 적은 JDBC 문장 수를 돌려준다. 통계는 SessionFactory 전체의 누적값이라
     * 다른 테스트가 남긴 비동기 스레드의 문장이 섞이면 수가 늘기만 한다. 같은 요청의 문장 수는 일정하므로 최솟값이 그 요청의 수다.
     */
    private fun statementsOfBatch(clientOrderId: ClientOrderId, operation: (attempt: Int) -> SyncCommand.ReceiveBatch.Operation): Long =
        (0 until ATTEMPTS).minOf { attempt ->
            awaitPublicationsDone(clientOrderId)
            val before = statistics.prepareStatementCount
            facade.receiveBatch(SyncCommand.ReceiveBatch(terminalId, listOf(operation(attempt))))
            statistics.prepareStatementCount - before
        }

    private fun checkout(sequence: Long, clientOrderId: ClientOrderId) =
        SyncCommand.ReceiveBatch.Operation(UUID.randomUUID(), sequence, CheckoutPayload(clientOrderId, BigDecimal("3502")))

    @Test
    fun `이미 요청된 주문의 멱등 checkout 요청은 9문장이고 재전송은 4문장이다`() {
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId)
        orders.create(command)
        // 첫 요청(OPEN에서 적용)은 커밋 직후 비동기 문장이 측정에 섞여 세지 않는다. 뒤 둘이 그것이 가라앉은 뒤에 센다.
        awaitPublicationsDone(command.clientOrderId)
        facade.receiveBatch(SyncCommand.ReceiveBatch(terminalId, listOf(checkout(1, command.clientOrderId))))
        // 순번 2..(1+ATTEMPTS)는 새 operationId의 멱등 요청이다. 그 뒤 순번 2를 다시 보내면 재전송이다.
        val operations = (0 until ATTEMPTS).map { checkout(2L + it, command.clientOrderId) }

        val idempotent = statementsOfBatch(command.clientOrderId) { operations[it] }
        val resent = statementsOfBatch(command.clientOrderId) { operations[0] }

        // `set local lock_timeout`은 통계에 잡히지 않는다(아래 합이 그것 없이 실측과 맞는다).
        // 배치마다: watermark 확인 SELECT, 응답용 watermark SELECT
        val perBatch = 2L
        // 항목 트랜잭션 판정: watermark 잠금 SELECT, 접수 기록 조회, watermark UPDATE, 접수 기록 INSERT
        val judged = 4L
        // 주문을 키로 잠그고 읽기: 주문 SELECT FOR UPDATE, line SELECT, checkout SELECT
        val orderLock = 3L
        // 주문 저장의 자식 SELECT 둘(line, checkout)은 바뀐 것이 없으면 나가지 않아야 한다
        assertEquals(perBatch + judged + orderLock, idempotent, "멱등 요청은 주문을 저장하지 않는다")
        // 재전송: 배치 공통 둘, watermark 잠금, 접수 기록 조회
        val lockAndFind = 2L
        assertEquals(perBatch + lockAndFind, resent, "재전송")
    }

    companion object {
        private const val ATTEMPTS = 5
    }
}
