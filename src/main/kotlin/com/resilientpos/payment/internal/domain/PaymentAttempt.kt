package com.resilientpos.payment.internal.domain

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentReconciliationRequested
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.foundation.isNegative
import com.resilientpos.foundation.isZero
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
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
 * 재결제로 만든 시도는 [retriedFrom]에 거절된 원래 시도를 가진다. 사실(`OrderPaymentRequested`)로 만든 시도는 null이고,
 * DB가 그런 승인 시도를 checkout 회차당 하나로 묶는다(`ux_payment_attempt_first_authorization`).
 * 그 `orderId`가 raw `UUID`인 이유는 `PaymentEvent`에 있다.
 *
 * [queryCount]는 `UNKNOWN`을 provider에 조회해도 결과를 못 얻은 횟수이고, [reconciliationRequestedAt]은 reconciliation case로 넘긴 시각이다(넘기지 않았으면 null).
 * 넘긴 시도는 조회로 확정하지 않고 reconciliation의 결론으로만 확정된다.
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
    val retriedFrom: PaymentId?,
    val unknownReason: PaymentUnknownReason?,
    val queryCount: Int,
    val reconciliationRequestedAt: Instant?,
) {

    /**
     * provider가 승인한 결말을 적용한다. [decline]·[markUnknown]과 함께 provider 결과를 적용하는 전이다.
     * 결말마다 메서드를 나눠 각 전이가 내는 사실의 타입이 하나로 정해진다.
     *
     * [PaymentAttemptStatus.REQUESTED]가 아니면 거절한다. "provider 결과는 한 번만 적용한다"가
     * 이 aggregate의 불변식이고([도메인 모델] §2), 두 번째 결과를 덮어쓰면 승인을 거절로 바꾸는
     * 일이 조용히 일어난다. 0원 시도는 만들자마자 [approveIfFree]로 승인되어 이 문으로 막힌다.
     *
     * 같은 사실이 두 번 배달돼도 여기까지 오지 않는 것이 정상 경로다 —
     * `PaymentManagement`가 결말이 난 시도면 provider를 부르지 않고 돌아선다.
     * 그 조기 반환을 건너뛴 호출만 이 예외를 본다.
     */
    fun approve(providerReference: String, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentApproved> {
        requireRequested()
        return approved(providerReference, ids, now)
    }

    /**
     * 0원 시도는 provider를 부를 것이 없어 바로 승인한다. 0원이 아니면 `null`이다.
     * 여기서 거절하면 전액 할인·프로모션 주문이 결제 단계에서 막힌다. provider를 부르지 않았으므로 참조가 없다.
     */
    fun approveIfFree(ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentApproved>? {
        if (!amount.isZero) return null
        requireRequested()
        return approved(null, ids, now)
    }

    /**
     * 결과를 모르던([markUnknown]) 시도를 provider 조회가 승인으로 가려낸 결말을 적용한다. 결과 상태와 사실은 [approve]와 같다.
     * `UNKNOWN`은 결과가 아니라 결과가 없다는 기록이라, 확정은 "provider 결과는 한 번만 적용한다"를 깨지 않고 결과를 처음 적용하는 일이다.
     *
     * 넘긴 시도는 [PAYMENT_IN_RECONCILIATION]으로 거절한다. [PaymentAttemptStatus.UNKNOWN]이 아니면 거절한다. 조회 대상은 `UNKNOWN`만 고르고 Facade가 잠근 뒤 다시 확인하므로
     * `REQUESTED`는 이 경로로 오지 않는다. 이미 승인·거절된 시도에 덮어쓰면 결과가 조용히 바뀐다.
     */
    fun confirmApproved(providerReference: String, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentApproved> {
        requireUnknownNotHandedOver()
        return approved(providerReference, ids, now)
    }

    /** 결과를 모르던 시도를 provider 조회가 거절로 가려낸 결말을 적용한다. 결과 상태와 사실은 [decline]과, 거절 조건은 [confirmApproved]와 같다. */
    fun confirmDeclined(providerReference: String?, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentEvent.Declined> {
        requireUnknownNotHandedOver()
        return declined(providerReference, ids, now)
    }

    /** 참조가 없는 승인은 [approveIfFree]만 만든다. provider가 승인했다면 참조가 늘 있다. */
    private fun approved(providerReference: String?, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentApproved> {
        val approved = PaymentApproved(
            eventId = ids.next(),
            occurredAt = now,
            paymentId = id.value,
            orderId = orderId,
            storeId = storeId,
            terminalId = terminalId,
            checkoutNo = checkoutNo,
            kind = kind,
            amount = amount,
            providerReference = providerReference,
        )
        return Transition(copy(PaymentAttemptStatus.APPROVED, providerReference), listOf(approved))
    }

    /** provider가 거절한 결말을 적용한다. 거절 조건은 [approve]와 같다. */
    fun decline(providerReference: String?, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentEvent.Declined> {
        requireRequested()
        return declined(providerReference, ids, now)
    }

    private fun declined(providerReference: String?, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentEvent.Declined> {
        val declined = PaymentEvent.Declined(
            eventId = ids.next(),
            occurredAt = now,
            paymentId = id,
            orderId = orderId,
            checkoutNo = checkoutNo,
            kind = kind,
            amount = amount,
            providerReference = providerReference,
        )
        return Transition(copy(PaymentAttemptStatus.DECLINED, providerReference), listOf(declined))
    }

    /**
     * provider 결과를 확인하지 못한 결말을 적용한다. 거절 조건은 [approve]와 같다.
     * [reason]은 `UNKNOWN`이 된 사유이고 이때 정해져 확정되어도 지워지지 않는다. 결과를 받지 못했으면 [PaymentUnknownReason.RESULT_UNCONFIRMED],
     * 승인이라는 결과는 받았지만 금액이 다르면 [PaymentUnknownReason.AMOUNT_MISMATCH]다.
     * provider가 무엇을 했는지 모르므로 참조를 남기지 않는다. 참조를 지어내면 그 참조가 거짓이 된다.
     */
    fun markUnknown(reason: PaymentUnknownReason, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentEvent.Unknown> {
        requireRequested()
        val unknown = PaymentEvent.Unknown(
            eventId = ids.next(),
            occurredAt = now,
            paymentId = id,
            orderId = orderId,
            checkoutNo = checkoutNo,
            kind = kind,
            amount = amount,
        )
        return Transition(copy(PaymentAttemptStatus.UNKNOWN, null, reason), listOf(unknown))
    }

    /**
     * 조회로도 결과를 확인하지 못했다는 사실을 기록한다. 조회 횟수를 하나 올리고, 그 값이 [limit] 이상이면 시도를 reconciliation case로 넘긴다.
     * 넘기면 넘긴 시각을 [now]로 정하고 [PaymentReconciliationRequested]([PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED])를 낸다.
     * 상한 전이면 사실 없이 횟수만 오른 새 인스턴스다. 경과 시간이 아니라 횟수로 세므로 provider에 실제로 그만큼 물은 근거가 남는다.
     *
     * 넘기지 않은 [PaymentAttemptStatus.UNKNOWN]만 받는다([requireUnknownNotHandedOver]).
     */
    fun recordUnknownQuery(limit: Int, ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentReconciliationRequested> {
        requireUnknownNotHandedOver()
        val queried = queryCount + 1
        if (queried < limit) return Transition(copy(status, providerReference, queryCount = queried), emptyList())
        val handedOver = copy(status, providerReference, queryCount = queried, reconciliationRequestedAt = now)
        return Transition(
            handedOver,
            listOf(handedOver.reconciliationRequested(PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED, null, null, ids, now)),
        )
    }

    /**
     * 조회가 승인이라고 답했지만 그 금액([reportedAmount], 통화 포함)이 시도 금액과 달라 확정하지 않고 바로 reconciliation case로 넘긴다.
     * 조회 횟수를 하나 올리고 넘긴 시각을 [now]로 정하며 [PaymentReconciliationRequested]([PaymentReconciliationReason.AMOUNT_MISMATCH])에
     * 알려 준 금액과 참조를 싣는다. 받는 조건은 [recordUnknownQuery]와 같다.
     */
    fun requestReconciliationForAmountMismatch(
        reportedAmount: Money,
        reportedProviderReference: String?,
        ids: IdentifierGenerator,
        now: Instant,
    ): Transition<PaymentAttempt, PaymentReconciliationRequested> {
        requireUnknownNotHandedOver()
        val handedOver = copy(status, providerReference, queryCount = queryCount + 1, reconciliationRequestedAt = now)
        return Transition(
            handedOver,
            listOf(
                handedOver.reconciliationRequested(
                    PaymentReconciliationReason.AMOUNT_MISMATCH, reportedAmount, reportedProviderReference, ids, now,
                ),
            ),
        )
    }

    /** 넘긴 뒤의 값(조회 횟수·불명 사유)을 싣는다. `UNKNOWN`이 된 때 사유가 정해지므로 없으면 불변식이 깨진 것이다(adapter는 이전 버전 행에 `RESULT_UNCONFIRMED`를 채운다). */
    private fun reconciliationRequested(
        reason: PaymentReconciliationReason,
        reportedAmount: Money?,
        reportedProviderReference: String?,
        ids: IdentifierGenerator,
        now: Instant,
    ) = PaymentReconciliationRequested(
        eventId = ids.next(),
        occurredAt = now,
        paymentId = id.value,
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = checkoutNo,
        amount = amount,
        reason = reason,
        unknownReason = checkNotNull(unknownReason) { "UNKNOWN 시도는 불명 사유를 가진다 paymentId=${id.value}" },
        queryCount = queryCount,
        reportedAmount = reportedAmount,
        reportedProviderReference = reportedProviderReference,
    )

    private fun requireRequested() {
        if (status != PaymentAttemptStatus.REQUESTED) {
            throw DomainException(
                PAYMENT_RESULT_ALREADY_APPLIED,
                "이미 결말이 난 결제 시도입니다",
                DomainErrorKind.CONFLICT,
            )
        }
    }

    private fun requireUnknown() {
        if (status != PaymentAttemptStatus.UNKNOWN) {
            throw DomainException(
                PAYMENT_RESULT_ALREADY_APPLIED,
                "결과를 모르는 결제 시도만 확정할 수 있습니다 paymentId=${id.value} status=$status",
                DomainErrorKind.CONFLICT,
            )
        }
    }

    /**
     * 조회 확정과 조회 기록·넘김이 받는 조건. 넘긴 시도는 reconciliation의 결론으로만 확정되므로 조회로 확정하거나 다시 넘기지 않는다.
     */
    private fun requireUnknownNotHandedOver() {
        requireUnknown()
        if (reconciliationRequestedAt != null) {
            throw DomainException(
                PAYMENT_IN_RECONCILIATION,
                "reconciliation으로 넘긴 결제 시도입니다 paymentId=${id.value}",
                DomainErrorKind.CONFLICT,
            )
        }
    }

    /**
     * 거절된 승인 시도를 같은 checkout 회차에서 다시 시도한다. 같은 주문·회차·금액의 `REQUESTED` 시도를 새로 만든다.
     * 새 provider 요청이라 식별자(곧 멱등 키)가 새로 나고, provider 호출은 생성 사실을 받는 리스너가 이어받는다.
     *
     * 거절된 승인 시도만 받는다. `UNKNOWN`은 돈이 이미 움직였을 수 있어 조회·reconciliation으로 수렴하기 전에는
     * 다시 시도할 수 없고, 진행 중이거나 승인된 시도는 다시 시도할 것이 없다.
     */
    fun retry(ids: IdentifierGenerator, now: Instant): Transition<PaymentAttempt, PaymentEvent.AttemptCreated> {
        if (kind != PaymentAttemptKind.AUTHORIZATION || status != PaymentAttemptStatus.DECLINED) {
            throw DomainException(
                PAYMENT_NOT_RETRYABLE,
                "거절된 승인 시도만 다시 시도할 수 있습니다 paymentId=${id.value} kind=$kind status=$status",
                DomainErrorKind.UNPROCESSABLE,
            )
        }

        val retried = PaymentAttempt(
            id = PaymentId(ids.next()),
            orderId = orderId,
            storeId = storeId,
            terminalId = terminalId,
            checkoutNo = checkoutNo,
            kind = kind,
            amount = amount,
            status = PaymentAttemptStatus.REQUESTED,
            // 새 provider 요청이다. 거절된 시도의 참조를 옮기면 다른 요청을 가리킨다.
            providerReference = null,
            retriedFrom = id,
            unknownReason = null,
            queryCount = 0,
            reconciliationRequestedAt = null,
        )
        val created = PaymentEvent.AttemptCreated(
            eventId = ids.next(),
            occurredAt = now,
            paymentId = retried.id,
            orderId = orderId,
            storeId = storeId,
            terminalId = terminalId,
            checkoutNo = checkoutNo,
            kind = kind,
            amount = amount,
        )
        return Transition(retried, listOf(created))
    }

    /**
     * 바뀐 값만 반영한 새 인스턴스. 나머지 값은 시도가 만들어질 때 확정됐고 바뀌지 않는다.
     * 사유는 `UNKNOWN`이 될 때만 정하고 확정 전이는 지우지 않는다. 조회 횟수와 넘긴 시각은 넘김 전이만 바꾼다.
     */
    private fun copy(
        status: PaymentAttemptStatus,
        providerReference: String?,
        unknownReason: PaymentUnknownReason? = this.unknownReason,
        queryCount: Int = this.queryCount,
        reconciliationRequestedAt: Instant? = this.reconciliationRequestedAt,
    ) = PaymentAttempt(
        id = id,
        orderId = orderId,
        storeId = storeId,
        terminalId = terminalId,
        checkoutNo = checkoutNo,
        kind = kind,
        amount = amount,
        status = status,
        providerReference = providerReference,
        retriedFrom = retriedFrom,
        unknownReason = unknownReason,
        queryCount = queryCount,
        reconciliationRequestedAt = reconciliationRequestedAt,
    )

    override fun equals(other: Any?) = other is PaymentAttempt && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val NEGATIVE_PAYMENT_AMOUNT = "NEGATIVE_PAYMENT_AMOUNT"
        const val INVALID_CHECKOUT_NO = "INVALID_CHECKOUT_NO"
        const val PAYMENT_RESULT_ALREADY_APPLIED = "PAYMENT_RESULT_ALREADY_APPLIED"
        const val PAYMENT_ATTEMPT_NOT_FOUND = "PAYMENT_ATTEMPT_NOT_FOUND"
        const val PAYMENT_NOT_RETRYABLE = "PAYMENT_NOT_RETRYABLE"
        const val PAYMENT_IN_RECONCILIATION = "PAYMENT_IN_RECONCILIATION"

        /** 찾는 시도가 없다. 응답은 404다. */
        fun notFound(): DomainException =
            DomainException(PAYMENT_ATTEMPT_NOT_FOUND, "결제 시도를 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

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
            retriedFrom: PaymentId?,
            unknownReason: PaymentUnknownReason?,
            queryCount: Int,
            reconciliationRequestedAt: Instant?,
        ): PaymentAttempt =
            PaymentAttempt(
                id, orderId, storeId, terminalId, checkoutNo, kind, amount, status, providerReference, retriedFrom,
                unknownReason, queryCount, reconciliationRequestedAt,
            )

        /**
         * 한 주문의 **승인** 시도를 `REQUESTED`로 만든다. 0원 시도는 이어서 [approveIfFree]로 승인한다.
         * 시도를 기록으로 남기고 승인 사실을 잇따라 내는 순서다. 승인만 내면 0원 주문의 시도가 조회에 보이지 않는다.
         * 취소·환불 시도는 이 factory가 만들지 않는다.
         */
        fun create(
            command: PaymentCommand.CreateAttempt,
            ids: IdentifierGenerator,
            now: Instant,
        ): Transition<PaymentAttempt, PaymentEvent.AttemptCreated> {
            if (command.amount.isNegative) {
                throw DomainException(NEGATIVE_PAYMENT_AMOUNT, "결제 금액은 음수일 수 없습니다")
            }
            if (command.checkoutNo < 1) {
                throw DomainException(INVALID_CHECKOUT_NO, "checkout 회차는 1 이상이어야 합니다")
            }

            val paymentId = PaymentId(ids.next())
            val attempt = PaymentAttempt(
                id = paymentId,
                orderId = command.orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                checkoutNo = command.checkoutNo,
                kind = PaymentAttemptKind.AUTHORIZATION,
                amount = command.amount,
                status = PaymentAttemptStatus.REQUESTED,
                // provider를 부르지 않았으므로 참조가 없다. 값을 지어내면 그 참조가 거짓이 된다.
                providerReference = null,
                retriedFrom = null,
                unknownReason = null,
                queryCount = 0,
                reconciliationRequestedAt = null,
            )

            val created = PaymentEvent.AttemptCreated(
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
            return Transition(attempt, listOf(created))
        }
    }
}
