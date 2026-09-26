package com.resilientpos.payment.internal.domain

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 거절된 승인 시도에서 같은 회차의 새 시도를 만드는 전이.
 */
class PaymentAttemptRetryTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val now = Instant.parse("2026-09-26T01:00:00Z")
    private val storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    private val terminalId = TerminalId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
    private val orderId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private class FixedIdentifierGenerator(ids: List<UUID>) : IdentifierGenerator {
        private val remaining = ArrayDeque(ids)
        override fun next(): UUID = remaining.removeFirstOrNull() ?: error("준비한 식별자를 다 썼다")
    }

    private val newPaymentId = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val eventId = UUID.fromString("00000000-0000-0000-0000-000000000012")
    private fun ids() = FixedIdentifierGenerator(listOf(newPaymentId, eventId))

    private fun attempt(
        status: PaymentAttemptStatus,
        kind: PaymentAttemptKind = PaymentAttemptKind.AUTHORIZATION,
    ) = PaymentAttempt.restore(
        id = PaymentId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = 2,
        kind = kind,
        amount = Money.of("4501", krw),
        status = status,
        providerReference = "PROVIDER-REF-1",
    )

    @Test
    fun `거절된 승인 시도는 같은 회차와 금액의 REQUESTED 시도를 새로 만들고 생성 사실을 낸다`() {
        val declined = attempt(PaymentAttemptStatus.DECLINED)

        val transition = declined.retry(ids(), now)

        val retried = transition.aggregate
        assertEquals(PaymentId(newPaymentId), retried.id)
        assertEquals(orderId, retried.orderId)
        assertEquals(storeId, retried.storeId)
        assertEquals(terminalId, retried.terminalId)
        assertEquals(2, retried.checkoutNo)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, retried.kind)
        assertEquals(Money.of("4501", krw), retried.amount)
        assertEquals(PaymentAttemptStatus.REQUESTED, retried.status)
        // 새 provider 요청이다. 거절된 시도의 참조를 옮기면 다른 요청을 가리킨다.
        assertNull(retried.providerReference)

        val created = assertIs<PaymentEvent.AttemptCreated>(transition.events.single())
        assertEquals(eventId, created.eventId)
        assertEquals(now, created.occurredAt)
        assertEquals(retried.id, created.paymentId)
        assertEquals(2, created.checkoutNo)
        assertEquals(Money.of("4501", krw), created.amount)

        assertEquals(PaymentAttemptStatus.DECLINED, declined.status)
    }

    @Test
    fun `거절되지 않은 시도는 재결제를 거절한다`() {
        listOf(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.UNKNOWN, PaymentAttemptStatus.APPROVED)
            .forEach { status ->
                val thrown = runCatching { attempt(status).retry(ids(), now) }.exceptionOrNull()

                val domain = assertIs<DomainException>(thrown, "$status")
                assertEquals(PaymentAttempt.PAYMENT_NOT_RETRYABLE, domain.code)
                assertEquals(DomainErrorKind.UNPROCESSABLE, domain.kind)
            }
    }

    @Test
    fun `거절된 취소 시도는 재결제 대상이 아니다`() {
        val thrown = runCatching {
            attempt(PaymentAttemptStatus.DECLINED, PaymentAttemptKind.CANCELLATION).retry(ids(), now)
        }.exceptionOrNull()

        assertEquals(PaymentAttempt.PAYMENT_NOT_RETRYABLE, assertIs<DomainException>(thrown).code)
    }
}
