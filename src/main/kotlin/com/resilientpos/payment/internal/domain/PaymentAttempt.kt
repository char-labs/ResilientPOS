package com.resilientpos.payment.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentApproved
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * 결제 시도 aggregate 루트. 한 주문의 한 회차 provider 요청과 그 결과를 소유한다.
 * `Order`와는 별개 aggregate라서 `orderId`로만 참조하고 사실은 이벤트로 건너간다.
 * 한 주문에 시도가 여럿 달린다. 승인이 거절되면 다음 시도가 생기고 취소·환불도 각각 별도 시도다.
 * 무엇을 요청한 시도인지는 [PaymentAttemptKind]가 말한다.
 * 그 `orderId`는 raw `UUID`다. `order.OrderId`를 쓰면 `order`가 이 모듈의 이벤트를 소비할 때 순환이 된다.
 */
class PaymentAttempt private constructor(
    val id: PaymentId,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val attemptNo: Int,
    val kind: PaymentAttemptKind,
    val amount: Money,
    val status: PaymentAttemptStatus,
    val providerReference: String?,
) {

    override fun equals(other: Any?) = other is PaymentAttempt && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val NEGATIVE_PAYMENT_AMOUNT = "NEGATIVE_PAYMENT_AMOUNT"
        const val INVALID_CHECKOUT_NO = "INVALID_CHECKOUT_NO"

        const val INVALID_ATTEMPT_NO = "INVALID_ATTEMPT_NO"

        /**
         * 한 주문의 `attemptNo`회차 **승인** 시도를 만든다.
         * 취소 시도를 만드는 factory는 그 전이를 만드는 PR이 더한다. 되돌릴 승인이 아직 없어서
         * 무엇을 참조해야 하는지(원 시도인지 provider 참조인지)를 지금 정할 근거가 없다.
         *
         * `attemptNo`는 명령이 아니라 **저장된 시도 이력에서 정해지는 값**이라 별도 인자로 받는다.
         * 시도들은 서로 다른 aggregate라서 자기 안에서 회차를 셀 수 없다. 부르는 쪽이 그 주문의
         * 마지막 회차 + 1을 넘긴다(개수 + 1이 아니다. 중간 회차가 지워지면 번호가 겹친다).
         * 동시에 두 요청이 같은 번호를 계산하는 경합은 `(orderId, attemptNo)` unique가 마지막으로 막는다.
         */
        fun create(
            command: CreatePaymentAttemptCommand,
            attemptNo: Int,
            ids: IdentifierGenerator,
            now: Instant,
        ): Transition<PaymentAttempt, PaymentEvent> {
            if (command.amount < Money.zero(command.amount.currency)) {
                throw DomainException(NEGATIVE_PAYMENT_AMOUNT, "결제 금액은 음수일 수 없습니다")
            }
            if (command.checkoutNo < 1) {
                throw DomainException(INVALID_CHECKOUT_NO, "checkout 회차는 1 이상이어야 합니다")
            }
            // 부르는 쪽이 정하는 값이라 여기 걸리면 클라이언트가 아니라 채번이 틀린 것이다.
            if (attemptNo < 1) {
                throw DomainException(INVALID_ATTEMPT_NO, "결제 시도 회차는 1 이상이어야 합니다")
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
                attemptNo = attemptNo,
                kind = PaymentAttemptKind.AUTHORIZATION,
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
                kind = attempt.kind,
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
                kind = attempt.kind,
                amount = command.amount,
                providerReference = null,
            )
            return Transition(attempt, listOf(created, approved))
        }
    }
}
