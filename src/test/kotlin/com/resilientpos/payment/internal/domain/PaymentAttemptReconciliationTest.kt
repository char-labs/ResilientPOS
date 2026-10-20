package com.resilientpos.payment.internal.domain

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentReconciliationRequested
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 조회로도 확정되지 않는 `UNKNOWN` 시도를 reconciliation case로 넘기는 두 전이와, 넘긴 시도의 조회 확정 거절.
 * 상한 판정은 Facade가 아니라 aggregate가 한다. 전이는 반환된 `Transition`을 값으로 비교한다.
 */
class PaymentAttemptReconciliationTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val now = Instant.parse("2026-10-20T01:00:00Z")
    private val handedAt = Instant.parse("2026-10-20T00:30:00Z")
    private val storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    private val terminalId = TerminalId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
    private val orderId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val paymentId = PaymentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val eventId = UUID.fromString("00000000-0000-0000-0000-000000000099")

    private class FixedIdentifierGenerator(ids: List<UUID>) : IdentifierGenerator {
        private val remaining = ArrayDeque(ids)
        override fun next(): UUID = remaining.removeFirstOrNull() ?: error("준비한 식별자를 다 썼다")
    }

    private fun ids() = FixedIdentifierGenerator(listOf(eventId))

    private fun attempt(
        status: PaymentAttemptStatus = PaymentAttemptStatus.UNKNOWN,
        unknownReason: PaymentUnknownReason? = PaymentUnknownReason.RESULT_UNCONFIRMED,
        queryCount: Int = 0,
        reconciliationRequestedAt: Instant? = null,
    ) = PaymentAttempt.restore(
        id = paymentId,
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = 2,
        kind = PaymentAttemptKind.AUTHORIZATION,
        amount = Money.of("4500", krw),
        status = status,
        providerReference = null,
        retriedFrom = null,
        unknownReason = unknownReason,
        queryCount = queryCount,
        reconciliationRequestedAt = reconciliationRequestedAt,
    )

    private fun requested(
        reason: PaymentReconciliationReason,
        unknownReason: PaymentUnknownReason,
        queryCount: Int,
        reportedAmount: Money? = null,
        reportedProviderReference: String? = null,
    ) = PaymentReconciliationRequested(
        eventId = eventId,
        occurredAt = now,
        paymentId = paymentId.value,
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = 2,
        amount = Money.of("4500", krw),
        reason = reason,
        unknownReason = unknownReason,
        queryCount = queryCount,
        reportedAmount = reportedAmount,
        reportedProviderReference = reportedProviderReference,
    )

    /** `PaymentAttempt.equals`는 식별자만 비교하므로 전이가 바꾸는 필드는 따로 비교한다. */
    private fun assertSameState(expected: PaymentAttempt, actual: PaymentAttempt) {
        assertEquals(expected, actual)
        assertEquals(expected.status, actual.status)
        assertEquals(expected.unknownReason, actual.unknownReason)
        assertEquals(expected.providerReference, actual.providerReference)
        assertEquals(expected.queryCount, actual.queryCount)
        assertEquals(expected.reconciliationRequestedAt, actual.reconciliationRequestedAt)
    }

    @Test
    fun `상한 전의 불명 조회는 횟수만 올리고 사실을 내지 않는다`() {
        val origin = attempt(queryCount = 1)

        val recorded = origin.recordUnknownQuery(3, ids(), now)

        assertSameState(attempt(queryCount = 2), recorded.aggregate)
        assertEquals(emptyList<PaymentReconciliationRequested>(), recorded.events)
        assertEquals(1, origin.queryCount)
    }

    @Test
    fun `상한에 닿는 불명 조회는 넘긴 시각을 정하고 QUERY_LIMIT_EXCEEDED 사실을 낸다`() {
        val origin = attempt(unknownReason = PaymentUnknownReason.AMOUNT_MISMATCH, queryCount = 2)

        val recorded = origin.recordUnknownQuery(3, ids(), now)

        val expected = Transition(
            attempt(unknownReason = PaymentUnknownReason.AMOUNT_MISMATCH, queryCount = 3, reconciliationRequestedAt = now),
            listOf(requested(PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED, PaymentUnknownReason.AMOUNT_MISMATCH, 3)),
        )
        assertSameState(expected.aggregate, recorded.aggregate)
        assertEquals(expected.events, recorded.events)
        assertNull(origin.reconciliationRequestedAt)
    }

    @Test
    fun `상한이 하나면 첫 불명 조회가 바로 넘긴다`() {
        val recorded = attempt().recordUnknownQuery(1, ids(), now)

        assertEquals(listOf(requested(PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED, PaymentUnknownReason.RESULT_UNCONFIRMED, 1)), recorded.events)
    }

    @Test
    fun `금액 불일치 넘김은 횟수를 올리고 넘긴 시각과 알려 준 금액·참조를 담은 AMOUNT_MISMATCH 사실을 낸다`() {
        val origin = attempt(queryCount = 1)
        val reported = Money.of("4501", krw)

        val handed = origin.requestReconciliationForAmountMismatch(reported, "PROVIDER-REF-9", ids(), now)

        val expected = Transition(
            attempt(queryCount = 2, reconciliationRequestedAt = now),
            listOf(requested(PaymentReconciliationReason.AMOUNT_MISMATCH, PaymentUnknownReason.RESULT_UNCONFIRMED, 2, reported, "PROVIDER-REF-9")),
        )
        assertSameState(expected.aggregate, handed.aggregate)
        assertEquals(expected.events, handed.events)
        assertEquals(1, origin.queryCount)
    }

    @Test
    fun `UNKNOWN이 아닌 시도는 어느 넘김도 받지 않고 원본이 바뀌지 않는다`() {
        val origin = attempt(status = PaymentAttemptStatus.APPROVED, unknownReason = null)

        val recordFailure = assertFailsWith<DomainException> { origin.recordUnknownQuery(3, ids(), now) }
        val mismatchFailure = assertFailsWith<DomainException> {
            origin.requestReconciliationForAmountMismatch(Money.of("4501", krw), "REF", ids(), now)
        }

        assertEquals(PaymentAttempt.PAYMENT_RESULT_ALREADY_APPLIED, recordFailure.code)
        assertEquals(PaymentAttempt.PAYMENT_RESULT_ALREADY_APPLIED, mismatchFailure.code)
        assertEquals(0, origin.queryCount)
        assertNull(origin.reconciliationRequestedAt)
    }

    @Test
    fun `이미 넘긴 시도는 어느 넘김도 다시 받지 않는다`() {
        val origin = attempt(queryCount = 3, reconciliationRequestedAt = handedAt)

        val recordFailure = assertFailsWith<DomainException> { origin.recordUnknownQuery(3, ids(), now) }
        val mismatchFailure = assertFailsWith<DomainException> {
            origin.requestReconciliationForAmountMismatch(Money.of("4501", krw), "REF", ids(), now)
        }

        assertEquals(PaymentAttempt.PAYMENT_IN_RECONCILIATION, recordFailure.code)
        assertEquals(DomainErrorKind.CONFLICT, recordFailure.kind)
        assertEquals(PaymentAttempt.PAYMENT_IN_RECONCILIATION, mismatchFailure.code)
        assertEquals(3, origin.queryCount)
        assertEquals(handedAt, origin.reconciliationRequestedAt)
    }

    @Test
    fun `넘긴 시도는 조회 확정을 거절하고 원본이 바뀌지 않는다`() {
        val origin = attempt(queryCount = 3, reconciliationRequestedAt = handedAt)

        val approveFailure = assertFailsWith<DomainException> { origin.confirmApproved("REF", ids(), now) }
        val declineFailure = assertFailsWith<DomainException> { origin.confirmDeclined("REF", ids(), now) }

        assertEquals(PaymentAttempt.PAYMENT_IN_RECONCILIATION, approveFailure.code)
        assertEquals(DomainErrorKind.CONFLICT, approveFailure.kind)
        assertEquals(PaymentAttempt.PAYMENT_IN_RECONCILIATION, declineFailure.code)
        assertEquals(PaymentAttemptStatus.UNKNOWN, origin.status)
        assertEquals(handedAt, origin.reconciliationRequestedAt)
    }

    @Test
    fun `새 시도와 재결제 시도의 조회 횟수는 0이고 넘기지 않은 상태다`() {
        val retried = attempt(status = PaymentAttemptStatus.DECLINED, unknownReason = null, queryCount = 5, reconciliationRequestedAt = handedAt)
            .retry(FixedIdentifierGenerator(listOf(UUID.randomUUID(), UUID.randomUUID())), now).aggregate

        assertEquals(0, retried.queryCount)
        assertNull(retried.reconciliationRequestedAt)
    }
}
