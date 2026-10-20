package com.resilientpos.reconciliation.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.ReconciliationFixtures
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.attemptRow
import com.resilientpos.reconciliation.caseCountFor
import com.resilientpos.reconciliation.caseRow
import com.resilientpos.reconciliation.caseStatus
import com.resilientpos.reconciliation.insertHandedOverAttempt
import com.resilientpos.reconciliation.insertUnhandedUnknownAttempt
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.reconciliation.isUnresolved
import com.resilientpos.reconciliation.publicationCount
import com.resilientpos.store.StoreId
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `ReconciliationManagement`의 열기·해소를 실제 DB·발행 기록과 함께 본다. 트랜잭션 합류, 잠금, 롤백이 인수 조건이라 통합이다.
 * 시도는 SQL로 넘긴 상태(`UNKNOWN`, 넘긴 시각 있음)로 넣고 Facade를 직접 부른다. Kafka를 거치는 흐름은 `ReconciliationFlowIntegrationTest`다.
 * DB를 다른 테스트와 함께 쓰므로 단언은 이 테스트가 만든 case·시도만 보고, counter는 호출 전후의 차이로 본다.
 */
class ReconciliationManagementIntegrationTest(
    @Autowired private val reconciliationManagement: ReconciliationManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val meterRegistry: MeterRegistry,
) : PostgresIntegrationTest() {

    private fun opened(reason: PaymentReconciliationReason) = meterRegistry.counter("reconciliation.case.opened", "reason", reason.name).count()

    private fun resolved(decision: ReconciliationDecision) =
        meterRegistry.counter("reconciliation.case.resolved", "decision", decision.name).count()

    private fun openCommand(paymentId: PaymentId, reason: PaymentReconciliationReason = PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED) =
        ReconciliationFixtures.open(reason = reason).copy(paymentId = paymentId, orderId = UUID.randomUUID())

    /** 넘긴 `UNKNOWN` 시도와 그 case를 만든다. */
    private fun openedCase(paymentId: PaymentId = jdbcTemplate.insertHandedOverAttempt()): Pair<PaymentId, ReconciliationCaseId> =
        paymentId to reconciliationManagement.open(openCommand(paymentId)).caseId

    @Test
    fun `case를 열면 MANUAL_REVIEW와 규칙 버전 1과 연 시각이 남고 해소 기록은 없으며 opened counter가 사유 태그로 하나 오른다`() {
        val paymentId = jdbcTemplate.insertHandedOverAttempt()
        val before = opened(PaymentReconciliationReason.AMOUNT_MISMATCH)

        val result = reconciliationManagement.open(openCommand(paymentId, PaymentReconciliationReason.AMOUNT_MISMATCH))

        assertEquals(ReconciliationCaseStatus.MANUAL_REVIEW, result.status)
        val row = jdbcTemplate.queryForList(
            "select status, rule_version, reason, payment_id, opened_at from t_reconciliation_case where id = ?", result.caseId.value,
        ).single()
        assertEquals("MANUAL_REVIEW", row["status"])
        assertEquals(ReconciliationCase.RULE_VERSION, row["rule_version"])
        assertEquals("AMOUNT_MISMATCH", row["reason"])
        assertEquals(paymentId.value, row["payment_id"])
        assertEquals(ReconciliationFixtures.handedAt, (row["opened_at"] as java.sql.Timestamp).toInstant())
        assertTrue(jdbcTemplate.isUnresolved(result.caseId))
        assertEquals(before + 1, opened(PaymentReconciliationReason.AMOUNT_MISMATCH))
    }

    @Test
    fun `같은 시도의 사실이 다시 와도 기존 case를 돌려주고 case와 열린 행과 counter는 그대로다`() {
        val paymentId = jdbcTemplate.insertHandedOverAttempt()
        val first = reconciliationManagement.open(openCommand(paymentId))
        val rowBefore = jdbcTemplate.caseRow(first.caseId)
        val before = opened(PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED)

        val again = reconciliationManagement.open(openCommand(paymentId))

        assertEquals(first, again)
        assertEquals(1, jdbcTemplate.caseCountFor(paymentId))
        assertEquals(rowBefore, jdbcTemplate.caseRow(first.caseId))
        assertEquals(before, opened(PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED))
    }

    @Test
    fun `승인으로 해소하면 한 커밋에 case RESOLVED와 해소 기록과 시도 APPROVED와 승인 발행 기록이 남고 resolved counter가 오른다`() {
        val (paymentId, caseId) = openedCase()
        val before = resolved(ReconciliationDecision.APPROVED)

        val result = reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId, providerReference = "PG-REF-7"))

        assertEquals(
            ReconciliationCommand.Resolve.Result(
                caseId, ReconciliationCaseStatus.RESOLVED, ReconciliationDecision.APPROVED, paymentId, PaymentAttemptStatus.APPROVED,
            ),
            result,
        )
        assertEquals("RESOLVED", jdbcTemplate.caseStatus(caseId))
        val row = jdbcTemplate.caseRow(caseId)
        assertEquals(ReconciliationFixtures.ownerId.value, row["resolved_by_owner_id"])
        assertNotNull(row["resolved_at"])
        assertEquals("APPROVED", row["decision"])
        assertEquals("PG-REF-7", row["provider_reference"])
        assertEquals(0, ReconciliationFixtures.amount.amount.compareTo(row["approved_amount"] as java.math.BigDecimal))
        assertEquals("KRW", row["approved_currency"])
        assertEquals("PG 가맹점 화면에서 승인 확인", row["note"])
        val attempt = jdbcTemplate.attemptRow(paymentId)
        assertEquals("APPROVED", attempt["status"])
        assertEquals("PG-REF-7", attempt["provider_reference"])
        assertEquals(1, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
        assertEquals(before + 1, resolved(ReconciliationDecision.APPROVED))
    }

    @Test
    fun `거절로 해소하면 시도가 DECLINED가 되고 승인 발행 기록은 없다`() {
        val (paymentId, caseId) = openedCase()
        val before = resolved(ReconciliationDecision.DECLINED)

        val result = reconciliationManagement.resolve(
            ReconciliationFixtures.resolve(caseId, ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = null),
        )

        assertEquals(ReconciliationDecision.DECLINED, result.decision)
        assertEquals(PaymentAttemptStatus.DECLINED, result.paymentStatus)
        assertEquals("RESOLVED", jdbcTemplate.caseStatus(caseId))
        assertEquals("DECLINED", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals(0, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
        assertEquals(before + 1, resolved(ReconciliationDecision.DECLINED))
    }

    @Test
    fun `승인 금액이 다르면 RECONCILIATION_AMOUNT_MISMATCH이고 case와 시도와 발행 기록은 그대로다`() {
        val (paymentId, caseId) = openedCase()

        val e = assertFailsWith<DomainException> {
            reconciliationManagement.resolve(
                ReconciliationFixtures.resolve(caseId, approvedAmount = Money.of("4501", ReconciliationFixtures.krw)),
            )
        }

        assertEquals(ReconciliationCase.RECONCILIATION_AMOUNT_MISMATCH, e.code)
        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertTrue(jdbcTemplate.isUnresolved(caseId))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals(0, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
    }

    @Test
    fun `다른 매장의 storeId로 해소하면 RECONCILIATION_CASE_NOT_FOUND이고 아무것도 바뀌지 않는다`() {
        val (paymentId, caseId) = openedCase()

        val e = assertFailsWith<DomainException> {
            reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId).copy(storeId = StoreId(UUID.randomUUID())))
        }

        assertEquals(ReconciliationCase.RECONCILIATION_CASE_NOT_FOUND, e.code)
        assertEquals(DomainErrorKind.NOT_FOUND, e.kind)
        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])
        assertTrue(jdbcTemplate.isUnresolved(caseId))
    }

    @Test
    fun `해소된 case에 같은 결론이 다시 오면 같은 결과이고 해소 기록과 counter는 그대로이며 payment를 부르지 않는다`() {
        val (paymentId, caseId) = openedCase()
        val first = reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId))
        val rowAfterFirst = jdbcTemplate.caseRow(caseId)
        val counterAfterFirst = resolved(ReconciliationDecision.APPROVED)
        // payment가 불렸다면 다시 확정해 승인 발행 기록이 둘이 된다. 그러지 못하게 시도를 되돌려 두고 아무 일도 없는지 본다.
        jdbcTemplate.update("update t_payment_attempt set status = 'UNKNOWN', provider_reference = null where id = ?", paymentId.value)

        val again = reconciliationManagement.resolve(
            ReconciliationFixtures.resolve(caseId, providerReference = "PG-REF-LATER", note = "응답을 잃어 다시 보냄"),
        )

        assertEquals(first, again)
        assertEquals(rowAfterFirst, jdbcTemplate.caseRow(caseId))
        assertEquals(counterAfterFirst, resolved(ReconciliationDecision.APPROVED))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals(1, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
    }

    @Test
    fun `해소된 case에 다른 결론이 오면 RECONCILIATION_CASE_ALREADY_RESOLVED이고 그대로다`() {
        val (paymentId, caseId) = openedCase()
        reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId))
        val rowBefore = jdbcTemplate.caseRow(caseId)

        val e = assertFailsWith<DomainException> {
            reconciliationManagement.resolve(
                ReconciliationFixtures.resolve(caseId, ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = null),
            )
        }

        assertEquals(ReconciliationCase.RECONCILIATION_CASE_ALREADY_RESOLVED, e.code)
        assertEquals(DomainErrorKind.CONFLICT, e.kind)
        assertEquals(rowBefore, jdbcTemplate.caseRow(caseId))
        assertEquals("APPROVED", jdbcTemplate.attemptRow(paymentId)["status"])
    }

    @Test
    fun `시도가 이미 해소 결론과 다른 결말이면 결제 상태를 담은 충돌로 거절하고 case는 그대로다`() {
        // 롤링 배포 중 이전 버전의 주기 작업이 넘긴 시도를 조회로 확정한 경우다.
        val (paymentId, caseId) = openedCase(jdbcTemplate.insertHandedOverAttempt(status = "APPROVED", providerReference = "OLD-REF"))

        val e = assertFailsWith<DomainException> {
            reconciliationManagement.resolve(
                ReconciliationFixtures.resolve(caseId, ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = null),
            )
        }

        assertEquals(ReconciliationCase.RECONCILIATION_PAYMENT_ALREADY_CONCLUDED, e.code)
        assertEquals(DomainErrorKind.CONFLICT, e.kind)
        assertTrue("APPROVED" in e.message, e.message)
        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertTrue(jdbcTemplate.isUnresolved(caseId))
        assertEquals("APPROVED", jdbcTemplate.attemptRow(paymentId)["status"])
    }

    @Test
    fun `시도가 이미 같은 결말이면 시도를 건드리지 않고 case만 해소한다`() {
        val (paymentId, caseId) = openedCase(jdbcTemplate.insertHandedOverAttempt(status = "APPROVED", providerReference = "OLD-REF"))

        val result = reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId, providerReference = "PG-REF-NEW"))

        assertEquals(PaymentAttemptStatus.APPROVED, result.paymentStatus)
        assertEquals("RESOLVED", jdbcTemplate.caseStatus(caseId))
        assertEquals("OLD-REF", jdbcTemplate.attemptRow(paymentId)["provider_reference"])
        assertEquals(0, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
    }

    @Test
    fun `payment가 결론을 거절하면 한 트랜잭션이 통째로 롤백되어 case도 그대로다`() {
        // 넘기지 않은 UNKNOWN은 case가 생길 수 없는 모순이라 payment가 PAYMENT_NOT_IN_RECONCILIATION으로 거절한다.
        val (paymentId, caseId) = openedCase(jdbcTemplate.insertUnhandedUnknownAttempt())

        val e = assertFailsWith<DomainException> { reconciliationManagement.resolve(ReconciliationFixtures.resolve(caseId)) }

        assertEquals("PAYMENT_NOT_IN_RECONCILIATION", e.code)
        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertTrue(jdbcTemplate.isUnresolved(caseId))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])
        assertNull(jdbcTemplate.attemptRow(paymentId)["provider_reference"])
    }
}
