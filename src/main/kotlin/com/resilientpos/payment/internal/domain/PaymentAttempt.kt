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
 * 식별은 [PaymentId] 하나가 하고 `orderId`는 참조일 뿐이라, 한 주문에 몇 건이 달리든 제약이 없다.
 * 무엇을 요청한 시도인지는 [PaymentAttemptKind]가 말한다.
 * 그 `orderId`는 raw `UUID`다. `order.OrderId`를 쓰면 `order`가 이 모듈의 이벤트를 소비할 때 순환이 된다.
 */
class PaymentAttempt private constructor(
    val id: PaymentId,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
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

        /**
         * 이미 저장된 시도를 그대로 재구성한다. 생성 불변식은 최초 `create` 때 검증됐으므로 다시 묻지 않는다.
         * persistence adapter의 `toDomain()`만 부른다.
         */
        fun restore(
            id: PaymentId,
            orderId: UUID,
            storeId: StoreId,
            terminalId: TerminalId,
            checkoutNo: Int,
            kind: PaymentAttemptKind,
            amount: Money,
            status: PaymentAttemptStatus,
            providerReference: String?,
        ): PaymentAttempt = PaymentAttempt(id, orderId, storeId, terminalId, checkoutNo, kind, amount, status, providerReference)

        /**
         * 한 주문의 **승인** 시도를 만든다.
         * 취소 시도를 만드는 factory는 그 전이를 만드는 PR이 더한다. 되돌릴 승인이 아직 없어서
         * 무엇을 참조해야 하는지(원 시도인지 provider 참조인지)를 지금 정할 근거가 없다.
         */
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
                kind = attempt.kind,
                amount = command.amount,
                providerReference = null,
            )
            return Transition(attempt, listOf(created, approved))
        }
    }
}
