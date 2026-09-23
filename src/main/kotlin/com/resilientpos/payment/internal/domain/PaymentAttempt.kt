package com.resilientpos.payment.internal.domain

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentDeclined
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentUnknown
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * 결제 시도 aggregate 루트. 한 주문의 한 회차 provider 요청과 그 결과를 소유한다.
 * `Order`와는 별개 aggregate라서 `orderId`로만 참조하고 사실은 이벤트로 건너간다.
 * 한 주문에 시도가 여럿 달릴 수 있다. 거절 뒤 재결제와 취소·환불은 각각 별도 시도다.
 * 식별은 [PaymentId] 하나가 하고 `orderId`는 참조일 뿐이라, 한 주문에 몇 건이 달리든 제약이 없다.
 * 무엇을 요청한 시도인지는 [PaymentAttemptKind]가 말한다.
 * 그 `orderId`가 raw `UUID`인 이유는 `PaymentEvent`에 있다.
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

    /**
     * provider가 말한 결말을 이 시도에 적용한다. 상태를 바꾼 새 인스턴스와 그 사실을 돌려준다.
     *
     * [PaymentAttemptStatus.REQUESTED]가 아니면 거절한다. "provider 결과는 한 번만 적용한다"가
     * 이 aggregate의 불변식이고([도메인 모델] §2), 두 번째 결과를 덮어쓰면 승인을 거절로 바꾸는
     * 일이 조용히 일어난다. 0원 시도는 만들어질 때 이미 승인이라 이 문으로 막힌다.
     *
     * 같은 사실이 두 번 배달돼도 여기까지 오지 않는 것이 정상 경로다 —
     * `PaymentManagement`가 결말이 난 시도면 provider를 부르지 않고 돌아선다.
     * 그 조기 반환을 건너뛴 호출만 이 예외를 본다.
     */
    fun applyAuthorization(
        outcome: AuthorizationOutcome,
        ids: IdentifierGenerator,
        now: Instant,
    ): Transition<PaymentAttempt, DomainEvent> {
        if (status != PaymentAttemptStatus.REQUESTED) {
            throw DomainException(
                PAYMENT_RESULT_ALREADY_APPLIED,
                "이미 결말이 난 결제 시도입니다",
                DomainErrorKind.CONFLICT,
            )
        }

        // 상태와 사실을 한 갈래에서 함께 정한다. 둘을 따로 매핑하면 결말 하나를 더할 때 컴파일러가 둘 다 강제하긴
        // 해도 서로 어긋나게 짝짓는 것은 막지 못한다.
        val (applied, event) = when (outcome) {
            is AuthorizationOutcome.Approved -> copy(PaymentAttemptStatus.APPROVED, outcome.providerReference) to
                PaymentApproved(
                    eventId = ids.next(),
                    occurredAt = now,
                    paymentId = id.value,
                    orderId = orderId,
                    storeId = storeId,
                    terminalId = terminalId,
                    checkoutNo = checkoutNo,
                    kind = kind,
                    amount = amount,
                    providerReference = outcome.providerReference,
                )

            is AuthorizationOutcome.Declined -> copy(PaymentAttemptStatus.DECLINED, outcome.providerReference) to
                PaymentDeclined(
                    eventId = ids.next(),
                    occurredAt = now,
                    paymentId = id,
                    orderId = orderId,
                    checkoutNo = checkoutNo,
                    kind = kind,
                    amount = amount,
                    providerReference = outcome.providerReference,
                )

            // provider가 무엇을 했는지 모른다. 참조를 지어내면 그 참조가 거짓이 된다.
            AuthorizationOutcome.Unknown -> copy(PaymentAttemptStatus.UNKNOWN, null) to
                PaymentUnknown(
                    eventId = ids.next(),
                    occurredAt = now,
                    paymentId = id,
                    orderId = orderId,
                    checkoutNo = checkoutNo,
                    kind = kind,
                    amount = amount,
                )
        }

        return Transition(applied, listOf<DomainEvent>(event))
    }

    /** 결말만 바뀐 새 인스턴스. 나머지 값은 시도가 만들어질 때 확정됐고 바뀌지 않는다. */
    private fun copy(status: PaymentAttemptStatus, providerReference: String?) = PaymentAttempt(
        id = id,
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = checkoutNo,
        kind = kind,
        amount = amount,
        status = status,
        providerReference = providerReference,
    )

    override fun equals(other: Any?) = other is PaymentAttempt && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val NEGATIVE_PAYMENT_AMOUNT = "NEGATIVE_PAYMENT_AMOUNT"
        const val INVALID_CHECKOUT_NO = "INVALID_CHECKOUT_NO"
        const val PAYMENT_RESULT_ALREADY_APPLIED = "PAYMENT_RESULT_ALREADY_APPLIED"
        const val PAYMENT_ATTEMPT_NOT_FOUND = "PAYMENT_ATTEMPT_NOT_FOUND"

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
         * 취소·환불 시도는 이 factory가 만들지 않는다.
         */
        fun create(
            command: CreatePaymentAttemptCommand,
            ids: IdentifierGenerator,
            now: Instant,
        ): Transition<PaymentAttempt, DomainEvent> {
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
                paymentId = paymentId.value,
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
