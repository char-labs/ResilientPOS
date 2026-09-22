package com.resilientpos.payment.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.order.OrderId
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentApproved
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant

/**
 * 결제 시도 aggregate 루트. 한 주문의 한 회차 provider 요청과 그 결과를 소유한다.
 * `Order`와는 별개 aggregate라서 `orderId`로만 참조하고 사실은 이벤트로 건너간다.
 */
class PaymentAttempt private constructor(
    val id: PaymentId,
    val orderId: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val attemptNo: Int,
    val amount: Money,
    val status: PaymentAttemptStatus,
    val providerReference: String?,
) {

    override fun equals(other: Any?) = other is PaymentAttempt && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val NEGATIVE_PAYMENT_AMOUNT = "NEGATIVE_PAYMENT_AMOUNT"
        const val INVALID_CHECKOUT_NO = "INVALID_CHECKOUT_NO"

        /**
         * 초기 정책은 주문당 시도 한 건이라 회차가 언제나 1이다. `(orderId, attemptNo)` unique가 그것을 강제한다.
         * 회차를 세려면 저장된 시도를 읽어야 하므로 채번은 repository가 생기는 PR이 정한다.
         * 취소를 별도 시도로 기록하거나 분할 결제를 다루는 PR이 이 상수를 걷어낸다.
         */
        const val FIRST_ATTEMPT_NO = 1

        fun create(
            command: CreatePaymentAttemptCommand,
            ids: IdentifierGenerator,
            now: Instant,
        ): Transition<PaymentAttempt, PaymentEvent> {
            if (command.amount < Money.zero(command.amount.currency)) {
                throw DomainException(NEGATIVE_PAYMENT_AMOUNT, "결제 금액은 음수일 수 없습니다")
            }
            if (command.checkoutNo < 1) {
                throw DomainException(INVALID_CHECKOUT_NO, "checkout 회차는 1 이상이어야 합니다")
            }

            // 0원은 provider를 부를 것이 없다. 여기서 거절하면 전액 할인·프로모션 주문이 결제 단계에서 막힌다.
            val zero = command.amount == Money.zero(command.amount.currency)
            val paymentId = PaymentId(ids.next())
            val attempt = PaymentAttempt(
                id = paymentId,
                orderId = command.orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                checkoutNo = command.checkoutNo,
                attemptNo = FIRST_ATTEMPT_NO,
                amount = command.amount,
                status = if (zero) PaymentAttemptStatus.APPROVED else PaymentAttemptStatus.REQUESTED,
                // provider를 부르지 않았으므로 참조가 없다. 값을 지어내면 그 참조가 거짓이 된다.
                providerReference = null,
            )

            val created = PaymentAttemptCreated(
                eventId = ids.next(),
                occurredAt = now,
                paymentId = paymentId,
                orderId = command.orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                checkoutNo = command.checkoutNo,
                attemptNo = attempt.attemptNo,
                amount = command.amount,
            )
            if (!zero) {
                return Transition(attempt, listOf(created))
            }

            // 시도를 기록으로 남기고 승인 사실을 잇따라 낸다. 승인만 내면 0원 주문의 시도가 조회에 보이지 않는다.
            val approved = PaymentApproved(
                eventId = ids.next(),
                occurredAt = now,
                paymentId = paymentId,
                orderId = command.orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                checkoutNo = command.checkoutNo,
                attemptNo = attempt.attemptNo,
                amount = command.amount,
                providerReference = null,
            )
            return Transition(attempt, listOf(created, approved))
        }
    }
}
