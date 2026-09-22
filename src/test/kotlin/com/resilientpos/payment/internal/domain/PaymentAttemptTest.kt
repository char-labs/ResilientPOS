package com.resilientpos.payment.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.order.OrderId
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
import kotlin.test.assertTrue

class PaymentAttemptTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val orderId = OrderId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
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

    private fun ids() = UuidV7IdentifierGenerator()

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

    private fun assertFailsWithDomain(block: () -> Unit): DomainException =
        runCatching(block).exceptionOrNull() as? DomainException ?: error("DomainException이 나지 않았다")
}
