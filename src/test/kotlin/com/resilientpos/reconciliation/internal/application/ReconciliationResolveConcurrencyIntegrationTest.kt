package com.resilientpos.reconciliation.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationFixtures
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.actionRows
import com.resilientpos.reconciliation.attemptRow
import com.resilientpos.reconciliation.caseStatus
import com.resilientpos.reconciliation.insertHandedOverAttempt
import com.resilientpos.reconciliation.publicationCount
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 같은 case의 해소가 겹칠 때 결제 확정과 감사 기록이 한 번만 남는지 본다.
 * case 행 잠금이 없으면 둘 다 `MANUAL_REVIEW`를 읽고 각자 OWNER 감사 줄을 넣는다. 늦은 쪽은 앞 커밋을 기다렸다가 멱등 결과를 받아야 한다.
 */
class ReconciliationResolveConcurrencyIntegrationTest(
    @Autowired private val reconciliationManagement: ReconciliationManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    @Test
    fun `같은 case의 해소가 동시에 둘 와도 시도 확정과 승인 발행 기록과 OWNER 감사 줄은 하나이고 늦은 쪽은 같은 결과를 받는다`() {
        val paymentId = jdbcTemplate.insertHandedOverAttempt()
        val caseId = reconciliationManagement.open(
            ReconciliationFixtures.open().copy(paymentId = paymentId, orderId = UUID.randomUUID()),
        ).caseId
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val results: List<ReconciliationCommand.Resolve.Result> = try {
            List(2) {
                executor.submit<ReconciliationCommand.Resolve.Result> {
                    start.await()
                    reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId))
                }
            }.also { start.countDown() }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.distinct().size, "두 호출이 서로 다른 결과를 받았다: $results")
        assertEquals("RESOLVED", jdbcTemplate.caseStatus(caseId))
        assertEquals("APPROVED", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals(1, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
        assertEquals(1, jdbcTemplate.actionRows(caseId).count { it["actor_kind"] == "OWNER" })
    }
}
