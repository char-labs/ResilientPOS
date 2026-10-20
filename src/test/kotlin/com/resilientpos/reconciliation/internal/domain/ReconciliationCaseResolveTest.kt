package com.resilientpos.reconciliation.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.ReconciliationFixtures
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReconciliationCaseResolveTest {

    private val now = Instant.parse("2026-10-20T09:00:00Z")
    private val krw = ReconciliationFixtures.krw

    private class FixedIdentifierGenerator(ids: List<UUID>) : IdentifierGenerator {
        private val remaining = ArrayDeque(ids)
        override fun next(): UUID = remaining.removeFirstOrNull() ?: error("준비한 식별자를 다 썼다")
    }

    private fun manualReview(): ReconciliationCase =
        ReconciliationCase.open(ReconciliationFixtures.open(), FixedIdentifierGenerator(listOf(UUID(0, 1)))).aggregate

    private fun resolve(
        target: ReconciliationCase,
        decision: ReconciliationDecision = ReconciliationDecision.APPROVED,
        providerReference: String? = "PG-REF-1",
        approvedAmount: Money? = ReconciliationFixtures.amount,
        note: String = "PG 가맹점 화면에서 승인 확인",
    ) = target.resolve(ReconciliationFixtures.resolve(target.id, decision, providerReference, approvedAmount, note), now)

    private fun assertRejected(code: String, kind: DomainErrorKind, block: (ReconciliationCase) -> Unit) {
        val target = manualReview()

        val error = assertFailsWith<DomainException> { block(target) }

        assertEquals(code, error.code)
        assertEquals(kind, error.kind)
        assertEquals(ReconciliationCaseStatus.MANUAL_REVIEW, target.status)
        assertNull(target.resolution)
    }

    @Test
    fun `승인으로 해소하면 RESOLVED가 되고 해소 기록이 점주와 시각과 결론과 근거를 담는다`() {
        val target = manualReview()

        val transition = resolve(target)

        assertEquals(ReconciliationCaseStatus.RESOLVED, transition.aggregate.status)
        assertTrue(transition.events.isEmpty())
        assertEquals(
            Resolution(
                ReconciliationFixtures.ownerId, now, ReconciliationDecision.APPROVED, "PG-REF-1", ReconciliationFixtures.amount,
                "PG 가맹점 화면에서 승인 확인",
            ),
            transition.aggregate.resolution,
        )
        assertEquals(target.openedAt, transition.aggregate.openedAt)
        assertEquals(ReconciliationCaseStatus.MANUAL_REVIEW, target.status)
        assertNull(target.resolution)
    }

    @Test
    fun `거절은 참조 없이도 해소된다`() {
        val transition = resolve(manualReview(), ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = null, note = "PG에서 취소")

        assertEquals(ReconciliationCaseStatus.RESOLVED, transition.aggregate.status)
        val resolution = checkNotNull(transition.aggregate.resolution)
        assertEquals(ReconciliationDecision.DECLINED, resolution.decision)
        assertNull(resolution.providerReference)
        assertNull(resolution.approvedAmount)
        assertEquals("PG에서 취소", resolution.note)
    }

    @Test
    fun `사유 500자는 통과한다`() {
        assertEquals(ReconciliationCaseStatus.RESOLVED, resolve(manualReview(), note = "가".repeat(500)).aggregate.status)
    }

    @Test
    fun `해소된 case에 같은 결론이 다시 오면 같은 인스턴스를 돌려주고 해소 기록을 바꾸지 않는다`() {
        val resolved = resolve(manualReview()).aggregate

        val again = resolved.resolve(ReconciliationFixtures.resolve(resolved.id, note = "응답을 잃어 다시 보냄"), now.plusSeconds(60))

        assertSame(resolved, again.aggregate)
        assertTrue(again.events.isEmpty())
        assertEquals(now, again.aggregate.resolution?.resolvedAt)
        assertEquals("PG 가맹점 화면에서 승인 확인", again.aggregate.resolution?.note)
    }

    @Test
    fun `해소된 case에 다른 결론이 오면 거절하고 원본은 그대로다`() {
        val resolved = resolve(manualReview()).aggregate
        val resolutionBefore = resolved.resolution

        val error = assertFailsWith<DomainException> {
            resolve(resolved, ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = null)
        }

        assertEquals(ReconciliationCase.RECONCILIATION_CASE_ALREADY_RESOLVED, error.code)
        assertEquals(DomainErrorKind.CONFLICT, error.kind)
        assertEquals(ReconciliationCaseStatus.RESOLVED, resolved.status)
        assertEquals(resolutionBefore, resolved.resolution)
    }

    @Test
    fun `승인 금액이 case 금액과 다르면 거절한다`() {
        listOf(Money.of("4501", krw), Money.of("4500", ReconciliationFixtures.usd)).forEach { different ->
            assertRejected(ReconciliationCase.RECONCILIATION_AMOUNT_MISMATCH, DomainErrorKind.UNPROCESSABLE) {
                resolve(it, approvedAmount = different)
            }
        }
    }

    @Test
    fun `승인에 참조나 금액이 없으면 거절한다`() {
        listOf("", "   ", null).forEach { reference ->
            assertRejected(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID) {
                resolve(it, providerReference = reference)
            }
        }
        assertRejected(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID) {
            resolve(it, approvedAmount = null)
        }
    }

    @Test
    fun `PG 승인 참조 255자는 통과하고 256자는 거절한다`() {
        assertEquals(ReconciliationCaseStatus.RESOLVED, resolve(manualReview(), providerReference = "R".repeat(255)).aggregate.status)
        listOf(ReconciliationDecision.APPROVED, ReconciliationDecision.DECLINED).forEach { decision ->
            assertRejected(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID) {
                resolve(it, decision = decision, providerReference = "R".repeat(256), approvedAmount = if (decision == ReconciliationDecision.APPROVED) it.amount else null)
            }
        }
    }

    @Test
    fun `거절에 승인 금액을 실으면 거절한다`() {
        assertRejected(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID) {
            resolve(it, ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = ReconciliationFixtures.amount)
        }
    }

    @Test
    fun `사유가 비었거나 500자를 넘으면 거절한다`() {
        listOf("", "   ", "\t\n", "가".repeat(501)).forEach { note ->
            assertRejected(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID) {
                resolve(it, note = note)
            }
        }
    }

    @Test
    fun `없는 case는 NOT_FOUND다`() {
        val error = ReconciliationCase.notFound()

        assertEquals(ReconciliationCase.RECONCILIATION_CASE_NOT_FOUND, error.code)
        assertEquals(DomainErrorKind.NOT_FOUND, error.kind)
    }
}
