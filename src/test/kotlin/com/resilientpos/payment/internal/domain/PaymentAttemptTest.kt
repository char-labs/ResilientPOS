package com.resilientpos.payment.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentApproved
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentAttemptTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val orderId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val storeId = StoreId(UUID.randomUUID())
    private val terminalId = TerminalId(UUID.randomUUID())
    private val now = Instant.parse("2026-09-22T01:00:00Z")

    private fun command(amount: String = "4500", checkoutNo: Int = 1) = CreatePaymentAttemptCommand(
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = checkoutNo,
        amount = Money.of(amount, krw),
    )

    /** ID 소비 순서를 고정한다. 무작위 생성기로는 "이벤트마다 다른 eventId를 쓴다"를 단언할 수 없다. */
    private class FixedIdentifierGenerator(ids: List<UUID>) : IdentifierGenerator {
        private val remaining = ArrayDeque(ids)
        override fun next(): UUID = remaining.removeFirstOrNull() ?: error("준비한 식별자를 다 썼다")
    }

    private fun ids() = FixedIdentifierGenerator(List(4) { UUID.fromString("00000000-0000-0000-0000-00000000000$it") })

    @Test
    fun `양수 금액 시도는 REQUESTED이고 생성 사실 하나만 낸다`() {
        val transition = PaymentAttempt.create(command(), ids(), now)

        assertEquals(PaymentAttemptStatus.REQUESTED, transition.aggregate.status)
        assertEquals(Money.of("4500", krw), transition.aggregate.amount)
        assertEquals(orderId, transition.aggregate.orderId)

        val created = transition.events.single() as PaymentAttemptCreated
        assertEquals(transition.aggregate.id, created.paymentId)
        assertEquals(orderId, created.orderId)
        assertEquals(1, created.attemptNo)
        assertEquals(now, created.occurredAt)
    }

    @Test
    fun `0원 시도는 provider를 부르지 않고 APPROVED이며 승인 사실까지 낸다`() {
        // order가 0원 주문도 똑같이 checkout을 확정하므로, payment가 여기서 거절하면
        // 전액 할인·프로모션 주문이 결제 단계에서 막힌다.
        val transition = PaymentAttempt.create(command(amount = "0"), ids(), now)

        assertEquals(PaymentAttemptStatus.APPROVED, transition.aggregate.status)
        assertEquals(2, transition.events.size)
        assertTrue(transition.events[0] is PaymentAttemptCreated)

        val approved = transition.events[1] as PaymentApproved
        assertEquals(transition.aggregate.id, approved.paymentId)
        assertEquals(orderId, approved.orderId)
        assertEquals(Money.of("0", krw), approved.amount)
        assertEquals(1, approved.attemptNo)
    }

    @Test
    fun `0원 승인에는 provider 참조가 없다`() {
        // provider를 부르지 않았으므로 참조가 있으면 그것이 거짓이다.
        val transition = PaymentAttempt.create(command(amount = "0"), ids(), now)

        assertEquals(null, transition.aggregate.providerReference)
        assertEquals(null, (transition.events[1] as PaymentApproved).providerReference)
    }

    @Test
    fun `음수 금액을 거절한다`() {
        val failure = assertFailsWithDomain { PaymentAttempt.create(command(amount = "-1"), ids(), now) }

        assertEquals(PaymentAttempt.NEGATIVE_PAYMENT_AMOUNT, failure.code)
        assertTrue(failure.message.isNotBlank())
        assertTrue(failure.message != failure.code)
    }

    @Test
    fun `1 미만인 checkout 회차를 거절한다`() {
        for (checkoutNo in listOf(0, -1)) {
            val failure = assertFailsWithDomain { PaymentAttempt.create(command(checkoutNo = checkoutNo), ids(), now) }

            assertEquals(PaymentAttempt.INVALID_CHECKOUT_NO, failure.code, checkoutNo.toString())
        }
    }

    @Test
    fun `초기 정책에서 시도 회차는 언제나 1이다`() {
        // 청사진이 정한 "주문당 attempt 한 건"이다. (orderId, attemptNo) unique가 그 정책을 강제한다.
        assertEquals(PaymentAttempt.FIRST_ATTEMPT_NO, PaymentAttempt.create(command(), ids(), now).aggregate.attemptNo)
        assertEquals(1, PaymentAttempt.create(command(checkoutNo = 7), ids(), now).aggregate.attemptNo)
    }

    @Test
    fun `이벤트마다 서로 다른 eventId를 쓴다`() {
        // eventId는 소비자 멱등성 기록 (consumer, eventId)의 키다. 두 이벤트가 같은 값을 쓰면
        // 0원 주문에서 소비자가 둘 중 하나를 중복으로 보고 버려 승인 사실이 조용히 사라진다.
        val transition = PaymentAttempt.create(command(amount = "0"), ids(), now)

        val created = transition.events[0]
        val approved = transition.events[1]
        assertNotEquals(created.eventId, approved.eventId)
        assertNotEquals(transition.aggregate.id.value, created.eventId)
        assertNotEquals(transition.aggregate.id.value, approved.eventId)
    }

    @Test
    fun `checkoutNo를 aggregate와 두 이벤트에 그대로 옮긴다`() {
        // checkoutNo는 시도를 order의 checkout에 잇는 유일한 값이고 금액 대조의 근거다.
        val transition = PaymentAttempt.create(command(amount = "0", checkoutNo = 7), ids(), now)

        assertEquals(7, transition.aggregate.checkoutNo)
        assertEquals(7, (transition.events[0] as PaymentAttemptCreated).checkoutNo)
        assertEquals(7, (transition.events[1] as PaymentApproved).checkoutNo)
    }

    @Test
    fun `매장과 단말을 두 이벤트에 그대로 옮긴다`() {
        val transition = PaymentAttempt.create(command(amount = "0"), ids(), now)

        val created = transition.events[0] as PaymentAttemptCreated
        val approved = transition.events[1] as PaymentApproved
        assertEquals(storeId, created.storeId)
        assertEquals(terminalId, created.terminalId)
        assertEquals(storeId, approved.storeId)
        assertEquals(terminalId, approved.terminalId)
    }

    @Test
    fun `거절 예외의 kind는 INVALID라서 400이 된다`() {
        // 기본값에 얹혀 있으면 누군가 kind를 바꿔도 아무도 모른다. HTTP 상태가 따라 바뀐다.
        assertEquals(DomainErrorKind.INVALID, assertFailsWithDomain { PaymentAttempt.create(command(amount = "-1"), ids(), now) }.kind)
        assertEquals(DomainErrorKind.INVALID, assertFailsWithDomain { PaymentAttempt.create(command(checkoutNo = 0), ids(), now) }.kind)
    }

    @Test
    fun `provider를 부른 적이 없으므로 어떤 경로에서도 참조가 없다`() {
        assertNull(PaymentAttempt.create(command(), ids(), now).aggregate.providerReference)
        assertNull(PaymentAttempt.create(command(amount = "0"), ids(), now).aggregate.providerReference)
    }

    private fun assertFailsWithDomain(block: () -> Unit): DomainException =
        runCatching(block).exceptionOrNull() as? DomainException ?: error("DomainException이 나지 않았다")
}
