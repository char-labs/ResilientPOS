package com.resilientpos.reconciliation.internal.domain

import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.payment.PaymentId
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * 조회로도 확정되지 않아 `payment`가 넘긴 시도의 대조 case aggregate 루트. 점주가 근거와 함께 해소할 때까지의 근거·규칙 버전·해소 기록을 소유한다.
 * `PaymentAttempt`와는 별개 aggregate라서 [paymentId]로만 참조하고, 시도 금액 등 근거는 넘긴 시점의 사본이다(바뀌지 않는 값이다).
 * 근거와 규칙과 해소 기록 없이 해결하지 않는다: 여는 전이는 규칙 버전과 연 시각([openedAt], 넘긴 시각)을, 해소 전이는 근거 검증과 [resolution]을 반드시 남긴다.
 * 자식 엔티티는 없다. `OPEN`·`INVESTIGATING`은 여는 전이 안의 중간 상태라 저장되지 않고, 해소는 case당 한 번이라 [resolution]은 값 하나다.
 */
class ReconciliationCase private constructor(
    val id: ReconciliationCaseId,
    val paymentId: PaymentId,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val reason: PaymentReconciliationReason,
    val status: ReconciliationCaseStatus,
    val ruleVersion: Int,
    val amount: Money,
    val unknownReason: PaymentUnknownReason,
    val queryCount: Int,
    val reportedAmount: Money?,
    val reportedProviderReference: String?,
    val openedAt: Instant,
    val resolution: Resolution?,
) {

    /**
     * 점주의 결론을 근거와 대조해 `RESOLVED`로 해소한다. 이벤트는 없다. 결제 시도에 결론을 적용하는 일은 호출하는 Facade가 한다.
     *
     * `MANUAL_REVIEW`에서만 해소한다. 이미 `RESOLVED`인 case에 같은 결론이 다시 오면(응답을 잃은 재요청) 해소 기록을 바꾸지 않고
     * 같은 인스턴스를 돌려주고, 다른 결론이면 [RECONCILIATION_CASE_ALREADY_RESOLVED]다.
     * 공백뿐인 참조는 없는 것으로 다뤄 해소 기록에 null로 남긴다.
     * 검증: 사유는 비어 있지 않고 [MAX_NOTE_LENGTH]자 이하, 참조는 [MAX_PROVIDER_REFERENCE_LENGTH]자 이하여야 하고, 승인은 참조와 승인 금액이 필수, 거절은 승인 금액이 없어야 하고(금액 불일치 case의 거절은 PG 취소 참조가 필수), 사유·참조에 NUL이 없어야 하며
     * (모두 [INVALID_RECONCILIATION_RESOLUTION]), 승인 금액은 통화까지 [amount]와 같아야 한다([RECONCILIATION_AMOUNT_MISMATCH]).
     */
    fun resolve(command: ReconciliationCommand.Resolve, now: Instant): Transition<ReconciliationCase, Nothing> =
        when (status) {
            ReconciliationCaseStatus.MANUAL_REVIEW -> {
                requireValidResolution(command)
                val resolution = Resolution(
                    command.ownerId, now, command.decision, command.providerReference?.takeIf { it.isNotBlank() }, command.approvedAmount, command.note,
                )
                Transition(withResolution(resolution), emptyList())
            }
            ReconciliationCaseStatus.RESOLVED ->
                if (resolvedDecision() == command.decision) {
                    Transition(this, emptyList())
                } else {
                    throw DomainException(
                        RECONCILIATION_CASE_ALREADY_RESOLVED,
                        "이미 다른 결론으로 해소된 case입니다 caseId=${id.value}",
                        DomainErrorKind.CONFLICT,
                    )
                }
            // 여는 전이가 한 번에 MANUAL_REVIEW까지 가므로 저장된 case는 이 상태가 아니다.
            ReconciliationCaseStatus.OPEN, ReconciliationCaseStatus.INVESTIGATING ->
                error("OPEN·INVESTIGATING은 여는 전이 안의 중간 상태라 저장된 case의 상태가 아니다 caseId=${id.value} status=$status")
            // 이 상태로 들어가는 전이가 아직 없다. 자동 조치 규칙을 만드는 PR이 이 분기의 처리를 정한다.
            ReconciliationCaseStatus.AUTO_REPAIRED ->
                error("AUTO_REPAIRED로 가는 전이는 아직 없다 caseId=${id.value}")
        }

    private fun resolvedDecision(): ReconciliationDecision =
        checkNotNull(resolution) { "RESOLVED case는 해소 기록을 가진다 caseId=${id.value}" }.decision

    private fun requireValidResolution(command: ReconciliationCommand.Resolve) {
        if (command.note.isBlank() || command.note.length > MAX_NOTE_LENGTH) {
            throw invalidResolution("사유는 비어 있지 않고 ${MAX_NOTE_LENGTH}자 이하여야 합니다")
        }
        if ((command.providerReference?.length ?: 0) > MAX_PROVIDER_REFERENCE_LENGTH) {
            throw invalidResolution("PG 승인 참조는 ${MAX_PROVIDER_REFERENCE_LENGTH}자 이하여야 합니다")
        }
        // PostgreSQL text가 받지 못하는 문자는 NUL(U+0000) 하나라 그것만 본다(OrderLine과 같다). 넘으면 커밋에서 실패해 500이 된다.
        if (command.note.contains(NUL) || command.providerReference?.contains(NUL) == true) {
            throw invalidResolution("사유와 PG 참조에 쓸 수 없는 문자가 있습니다")
        }
        when (command.decision) {
            ReconciliationDecision.APPROVED -> {
                if (command.providerReference.isNullOrBlank() || command.approvedAmount == null) {
                    throw invalidResolution("승인에는 PG 승인 참조와 승인 금액이 필요합니다")
                }
                if (command.approvedAmount != amount) {
                    throw DomainException(
                        RECONCILIATION_AMOUNT_MISMATCH,
                        "승인 금액이 case 금액과 다릅니다 caseId=${id.value}",
                        DomainErrorKind.UNPROCESSABLE,
                    )
                }
            }
            ReconciliationDecision.DECLINED -> {
                if (command.approvedAmount != null) throw invalidResolution("거절에는 승인 금액을 실을 수 없습니다")
                // 금액이 다르게 승인됐다는 보고가 있는 case는 승인된 돈을 되돌린 근거(PG 취소 참조)가 있어야 거절할 수 있다.
                val isAmountMismatch = reason == PaymentReconciliationReason.AMOUNT_MISMATCH || unknownReason == PaymentUnknownReason.AMOUNT_MISMATCH
                if (isAmountMismatch && command.providerReference.isNullOrBlank()) {
                    throw invalidResolution("금액 불일치 case의 거절에는 PG 취소 참조가 필요합니다")
                }
            }
        }
    }

    private fun invalidResolution(message: String) =
        DomainException(INVALID_RECONCILIATION_RESOLUTION, message, DomainErrorKind.INVALID)

    private fun withResolution(resolution: Resolution) =
        ReconciliationCase(
            id, paymentId, orderId, storeId, terminalId, checkoutNo, reason, ReconciliationCaseStatus.RESOLVED, ruleVersion, amount,
            unknownReason, queryCount, reportedAmount, reportedProviderReference, openedAt, resolution,
        )

    override fun equals(other: Any?) = other is ReconciliationCase && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        private const val NUL = '\u0000'

        const val RECONCILIATION_CASE_NOT_FOUND = "RECONCILIATION_CASE_NOT_FOUND"
        const val RECONCILIATION_CASE_ALREADY_RESOLVED = "RECONCILIATION_CASE_ALREADY_RESOLVED"
        const val RECONCILIATION_PAYMENT_ALREADY_CONCLUDED = "RECONCILIATION_PAYMENT_ALREADY_CONCLUDED"
        const val INVALID_RECONCILIATION_RESOLUTION = "INVALID_RECONCILIATION_RESOLUTION"
        const val RECONCILIATION_AMOUNT_MISMATCH = "RECONCILIATION_AMOUNT_MISMATCH"

        /** 분류 규칙 버전. 규칙을 바꾸면 올려서, 옛 case가 왜 그 상태로 갔는지 남긴다. */
        const val RULE_VERSION = 1

        /** 컬럼 `t_reconciliation_case.note varchar(500)`을 따라간다. 컬럼을 바꾸면 함께 바꾼다. */
        const val MAX_NOTE_LENGTH = 500

        /** 컬럼 `provider_reference varchar(255)`(`t_reconciliation_case`, `t_payment_attempt`)를 따라간다. 넘으면 커밋에서 실패해 500이 된다. */
        const val MAX_PROVIDER_REFERENCE_LENGTH = 255

        fun notFound(): DomainException =
            DomainException(RECONCILIATION_CASE_NOT_FOUND, "대조 case를 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        fun restore(
            id: ReconciliationCaseId,
            paymentId: PaymentId,
            orderId: UUID,
            storeId: StoreId,
            terminalId: TerminalId,
            checkoutNo: Int,
            reason: PaymentReconciliationReason,
            status: ReconciliationCaseStatus,
            ruleVersion: Int,
            amount: Money,
            unknownReason: PaymentUnknownReason,
            queryCount: Int,
            reportedAmount: Money?,
            reportedProviderReference: String?,
            openedAt: Instant,
            resolution: Resolution?,
        ): ReconciliationCase =
            ReconciliationCase(
                id, paymentId, orderId, storeId, terminalId, checkoutNo, reason, status, ruleVersion, amount,
                unknownReason, queryCount, reportedAmount, reportedProviderReference, openedAt, resolution,
            )

        /**
         * 넘겨받은 시도로 case를 연다. `OPEN` → `INVESTIGATING`(규칙 평가) → 분류 결과 상태를 한 번에 거쳐 분류 결과만 남긴다.
         * 연 시각은 넘긴 시각(`command.occurredAt`)이고 해소 기록은 없다. 이벤트는 없다. 분류는 사유에 대한 else 없는 `when`이라 사유가 늘면 분류를 정하지 않고는 컴파일되지 않는다.
         */
        fun open(command: ReconciliationCommand.Open, ids: IdentifierGenerator): Transition<ReconciliationCase, Nothing> {
            val caseId = ReconciliationCaseId(ids.next())
            val classified = classify(command.reason)
            val opened = ReconciliationCase(
                caseId, command.paymentId, command.orderId, command.storeId, command.terminalId, command.checkoutNo, command.reason,
                classified, RULE_VERSION, command.amount, command.unknownReason, command.queryCount,
                command.reportedAmount, command.reportedProviderReference, command.occurredAt, null,
            )
            return Transition(opened, emptyList())
        }

        /** 분류 규칙 v1: 두 조건 모두 사람 없이 안전하게 내릴 결론이 없어 `MANUAL_REVIEW`로 보낸다. */
        private fun classify(reason: PaymentReconciliationReason): ReconciliationCaseStatus =
            when (reason) {
                PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED,
                PaymentReconciliationReason.AMOUNT_MISMATCH,
                -> ReconciliationCaseStatus.MANUAL_REVIEW
            }
    }
}
