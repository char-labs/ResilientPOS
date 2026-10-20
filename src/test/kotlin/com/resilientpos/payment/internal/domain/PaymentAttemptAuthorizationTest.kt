package com.resilientpos.payment.internal.domain

import com.resilientpos.contract.PaymentAttemptKind
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
import kotlin.test.assertTrue

/**
 * provider 결과를 시도에 적용하는 전이. 세 결말이 각각 어떤 상태와 사실이 되는지를 고정한다.
 */
class PaymentAttemptAuthorizationTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val orderId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val now = Instant.parse("2026-09-23T01:00:00Z")

    private class FixedIdentifierGenerator(ids: List<UUID>) : IdentifierGenerator {
        private val remaining = ArrayDeque(ids)
        override fun next(): UUID = remaining.removeFirstOrNull() ?: error("준비한 식별자를 다 썼다")
    }

    private fun ids(seed: Int = 0) =
        FixedIdentifierGenerator(List(4) { UUID.fromString("00000000-0000-0000-0000-0000000${seed}000$it") })

    private fun requested(amount: String = "4500"): PaymentAttempt =
        PaymentAttempt.create(
            PaymentCommand.CreateAttempt(
                orderId = orderId,
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = 1,
                amount = Money.of(amount, krw),
            ),
            ids(),
            now,
        ).aggregate

    @Test
    fun `승인을 적용하면 APPROVED가 되고 provider 참조가 남는다`() {
        val attempt = requested()

        val transition = attempt.approve("PROVIDER-REF-1", ids(1), now)

        assertEquals(PaymentAttemptStatus.APPROVED, transition.aggregate.status)
        assertEquals("PROVIDER-REF-1", transition.aggregate.providerReference)
        val approved = transition.events.single()
        assertEquals(attempt.id.value, approved.paymentId)
        assertEquals("PROVIDER-REF-1", approved.providerReference)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, approved.kind)
        assertEquals(Money.of("4500", krw), approved.amount)
    }

    @Test
    fun `거절을 적용하면 DECLINED가 되고 거절 사실을 낸다`() {
        val attempt = requested()

        val transition = attempt.decline("PROVIDER-REF-2", ids(1), now)

        assertEquals(PaymentAttemptStatus.DECLINED, transition.aggregate.status)
        assertEquals("PROVIDER-REF-2", transition.aggregate.providerReference)
        val declined = transition.events.single()
        assertEquals(attempt.id, declined.paymentId)
        assertEquals("PROVIDER-REF-2", declined.providerReference)
    }

    @Test
    fun `미확인을 적용하면 UNKNOWN이 되고 참조가 없다`() {
        val attempt = requested()

        val transition = attempt.markUnknown(PaymentUnknownReason.RESULT_UNCONFIRMED, ids(1), now)

        assertEquals(PaymentAttemptStatus.UNKNOWN, transition.aggregate.status)
        // provider가 무엇을 했는지 모르는 상태다. 참조를 지어내면 그 참조가 거짓이 된다.
        assertNull(transition.aggregate.providerReference)
        val unknown = transition.events.single()
        assertEquals(attempt.id, unknown.paymentId)
    }

    @Test
    fun `미확인을 적용하면 받은 사유가 시도에 남고 처음 시도에는 사유가 없다`() {
        val attempt = requested()

        assertNull(attempt.unknownReason)
        PaymentUnknownReason.entries.forEach { reason ->
            assertEquals(reason, attempt.markUnknown(reason, ids(1), now).aggregate.unknownReason, reason.name)
        }
    }

    @Test
    fun `원본 시도는 그대로 남는다`() {
        val attempt = requested()

        attempt.approve("PROVIDER-REF-3", ids(1), now)

        assertEquals(PaymentAttemptStatus.REQUESTED, attempt.status)
        assertNull(attempt.providerReference)
    }

    @Test
    fun `이미 결말이 난 시도에 다시 적용하면 거절한다`() {
        val approved = requested().approve("PROVIDER-REF-4", ids(1), now).aggregate
        val applications = mapOf<String, (PaymentAttempt) -> Unit>(
            "approve" to { it.approve("PROVIDER-REF-6", ids(2), now) },
            "decline" to { it.decline(null, ids(2), now) },
            "markUnknown" to { it.markUnknown(PaymentUnknownReason.RESULT_UNCONFIRMED, ids(2), now) },
        )

        for ((name, apply) in applications) {
            val domain = assertFailsWith<DomainException>(name) { apply(approved) }
            assertEquals(PaymentAttempt.PAYMENT_RESULT_ALREADY_APPLIED, domain.code, name)
            assertEquals(DomainErrorKind.CONFLICT, domain.kind, name)
        }
    }

    @Test
    fun `결과 사실은 생성기에서 새 eventId를 받고 적용 시각을 쓴다`() {
        val attempt = requested()
        val generator = ids(1)

        val event = attempt.approve("PROVIDER-REF-5", generator, now).events.single()

        // 시도의 식별자를 eventId로 되쓰면 소비자 멱등 기록 (consumer, eventId)에서 서로 다른 사실이 하나로 접힌다.
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000010000"), event.eventId)
        assertTrue(event.eventId != attempt.id.value)
        assertEquals(now, event.occurredAt)
    }
}
