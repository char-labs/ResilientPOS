package com.resilientpos.payment.internal.domain

import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
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
 * 결과를 모르는(`UNKNOWN`) 시도를 provider 조회 결과로 확정하는 전이.
 * 결과 행 상태와 사실은 `approve`·`decline`과 같고, 확정은 `UNKNOWN`에서만 받는다.
 */
class PaymentAttemptConfirmationTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val now = Instant.parse("2026-10-01T01:00:00Z")

    private class FixedIdentifierGenerator(ids: List<UUID>) : IdentifierGenerator {
        private val remaining = ArrayDeque(ids)
        override fun next(): UUID = remaining.removeFirstOrNull() ?: error("준비한 식별자를 다 썼다")
    }

    private fun ids(seed: Int = 0) =
        FixedIdentifierGenerator(List(4) { UUID.fromString("00000000-0000-0000-0000-0000000${seed}000$it") })

    private fun requested(): PaymentAttempt =
        PaymentAttempt.create(
            PaymentCommand.CreateAttempt(
                orderId = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = 1,
                amount = Money.of("4500", krw),
            ),
            ids(),
            now,
        ).aggregate

    private fun unknown(): PaymentAttempt = requested().markUnknown(PaymentUnknownReason.AMOUNT_MISMATCH, ids(1), now).aggregate

    @Test
    fun `UNKNOWN을 승인으로 확정하면 APPROVED가 되고 approve와 같은 사실을 낸다`() {
        val origin = requested()
        val attempt = origin.markUnknown(PaymentUnknownReason.AMOUNT_MISMATCH, ids(1), now).aggregate

        val confirmed = attempt.confirmApproved("PROVIDER-REF-1", ids(2), now)

        assertEquals(PaymentAttemptStatus.APPROVED, confirmed.aggregate.status)
        assertEquals("PROVIDER-REF-1", confirmed.aggregate.providerReference)
        assertEquals(origin.approve("PROVIDER-REF-1", ids(2), now).events, confirmed.events)
        assertEquals(PaymentAttemptStatus.UNKNOWN, attempt.status)
        assertNull(attempt.providerReference)
    }

    @Test
    fun `UNKNOWN을 거절로 확정하면 DECLINED가 되고 decline과 같은 사실을 낸다`() {
        val origin = requested()
        val attempt = origin.markUnknown(PaymentUnknownReason.AMOUNT_MISMATCH, ids(1), now).aggregate

        val confirmed = attempt.confirmDeclined("PROVIDER-REF-2", ids(2), now)

        assertEquals(PaymentAttemptStatus.DECLINED, confirmed.aggregate.status)
        assertEquals("PROVIDER-REF-2", confirmed.aggregate.providerReference)
        assertEquals(origin.decline("PROVIDER-REF-2", ids(2), now).events, confirmed.events)
    }

    @Test
    fun `확정해도 UNKNOWN이 된 때 정한 사유를 지우지 않는다`() {
        assertEquals(PaymentUnknownReason.AMOUNT_MISMATCH, unknown().confirmApproved("REF", ids(2), now).aggregate.unknownReason)
        assertEquals(PaymentUnknownReason.AMOUNT_MISMATCH, unknown().confirmDeclined(null, ids(2), now).aggregate.unknownReason)
    }

    @Test
    fun `참조 없는 거절로도 확정한다`() {
        val confirmed = unknown().confirmDeclined(null, ids(2), now)

        assertEquals(PaymentAttemptStatus.DECLINED, confirmed.aggregate.status)
        assertNull(confirmed.aggregate.providerReference)
        assertNull(confirmed.events.single().providerReference)
    }

    @Test
    fun `UNKNOWN이 아닌 시도의 확정은 거절하고 원본은 그대로다`() {
        val approved = requested().approve("PROVIDER-REF-3", ids(1), now).aggregate
        val declined = requested().decline("PROVIDER-REF-4", ids(1), now).aggregate
        val attempts = mapOf("REQUESTED" to requested(), "APPROVED" to approved, "DECLINED" to declined)

        for ((name, attempt) in attempts) {
            val before = attempt.status to attempt.providerReference
            val confirmations = mapOf<String, () -> Unit>(
                "confirmApproved" to { attempt.confirmApproved("PROVIDER-REF-5", ids(2), now) },
                "confirmDeclined" to { attempt.confirmDeclined(null, ids(2), now) },
            )
            for ((method, confirm) in confirmations) {
                val domain = assertFailsWith<DomainException>("$name $method") { confirm() }
                assertEquals(PaymentAttempt.PAYMENT_RESULT_ALREADY_APPLIED, domain.code, "$name $method")
                assertEquals(DomainErrorKind.CONFLICT, domain.kind, "$name $method")
            }
            assertEquals(before, attempt.status to attempt.providerReference, name)
        }
    }
}
