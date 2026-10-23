package com.resilientpos.reconciliation.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationFixtures
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.attemptRow
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.caseRow
import com.resilientpos.reconciliation.caseStatus
import com.resilientpos.reconciliation.insertHandedOverAttempt
import com.resilientpos.reconciliation.publicationCount
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 같은 case의 해소가 겹칠 때 결제 확정과 해소가 한 번만 일어나는지 본다.
 * case 행 잠금이 없으면 둘 다 `MANUAL_REVIEW`를 읽고 둘 다 "처음 해소"로 세어 resolved counter가 둘 오르고, 늦게 커밋한 쪽이 해소 컬럼을 덮어쓴다.
 * 잠금이 있으면 늦은 쪽은 앞 커밋을 기다렸다가 `RESOLVED`를 읽고 멱등 결과를 받아 counter와 해소 기록이 먼저 커밋한 요청의 것으로 남는다.
 * 두 요청은 사유가 다르다. 해소 기록은 둘 중 하나의 사유와 일치해야 한다.
 */
class ReconciliationResolveConcurrencyIntegrationTest(
    private val reconciliationManagement: ReconciliationManagement,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : PostgresIntegrationTest() {

    @Test
    fun `같은 case의 해소가 동시에 둘 와도 시도 확정과 승인 발행 기록과 처음 해소는 하나이고 늦은 쪽은 같은 결과를 받으며 해소 기록은 한 요청의 것이다`() {
        val paymentId = jdbcTemplate.insertHandedOverAttempt()
        val caseId = reconciliationManagement.open(
            ReconciliationFixtures.open().copy(paymentId = paymentId, orderId = UUID.randomUUID()),
        ).caseId
        val before = meterRegistry.counter("reconciliation.case.resolved", "decision", "APPROVED").count()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val results: List<ReconciliationCommand.Resolve.Result> = try {
            List(2) { index ->
                executor.submit<ReconciliationCommand.Resolve.Result> {
                    start.await()
                    reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId, note = "요청 $index"))
                }
            }.also { start.countDown() }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.distinct().size, "두 호출이 서로 다른 결과를 받았다: $results")
        assertEquals("RESOLVED", jdbcTemplate.caseStatus(caseId))
        assertEquals("APPROVED", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals(1, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
        assertEquals(before + 1, meterRegistry.counter("reconciliation.case.resolved", "decision", "APPROVED").count())
        val row = jdbcTemplate.caseRow(caseId)
        assertEquals("APPROVED", row["decision"])
        assertTrue(row["note"] in setOf("요청 0", "요청 1"), "${row["note"]}")
    }
}
