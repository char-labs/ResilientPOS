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
 * 조회로도 확정되지 않아 `payment`가 넘긴 시도의 대조 case aggregate 루트. 점주가 근거와 함께 해소할 때까지의 근거·규칙 버전·감사 기록을 소유한다.
 * `PaymentAttempt`와는 별개 aggregate라서 [paymentId]로만 참조하고, 시도 금액 등 근거는 넘긴 시점의 사본이다(바뀌지 않는 값이다).
 * 근거와 규칙과 감사 기록 없이 해결하지 않는다: 여는 전이는 규칙 버전을, 해소 전이는 근거 검증과 OWNER 감사 줄을 반드시 남긴다.
 * 해소한 결론은 필드로 따로 두지 않고 `RESOLVED` 감사 줄에서 읽는다([actions]는 넣기만 하고 바꾸지 않는다).
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
    val actions: List<ReconciliationCaseAction>,
) {

    /**
     * 점주의 결론을 근거와 대조해 `RESOLVED`로 해소한다. 이벤트는 없다. 결제 시도에 결론을 적용하는 일은 호출하는 Facade가 한다.
     *
     * `MANUAL_REVIEW`에서만 해소한다. 이미 `RESOLVED`인 case에 같은 결론이 다시 오면(응답을 잃은 재요청) 감사 줄을 더하지 않고
     * 같은 인스턴스를 돌려주고, 다른 결론이면 [RECONCILIATION_CASE_ALREADY_RESOLVED]다.
     * 검증: 사유는 비어 있지 않고 [MAX_NOTE_LENGTH]자 이하, 참조는 [MAX_PROVIDER_REFERENCE_LENGTH]자 이하여야 하고, 승인은 참조와 승인 금액이 필수, 거절은 승인 금액이 없어야 하며
     * (모두 [INVALID_RECONCILIATION_RESOLUTION]), 승인 금액은 통화까지 [amount]와 같아야 한다([RECONCILIATION_AMOUNT_MISMATCH]).
     */
    fun resolve(command: ReconciliationCommand.Resolve, ids: IdentifierGenerator, now: Instant): Transition<ReconciliationCase, Nothing> =
        when (status) {
            ReconciliationCaseStatus.MANUAL_REVIEW -> {
                requireValidResolution(command)
                val line = ReconciliationCaseAction(
                    ReconciliationCaseActionId(ids.next()), ReconciliationCaseStatus.RESOLVED, ReconciliationActorKind.OWNER, command.ownerId, now,
                    command.decision, command.providerReference, command.approvedAmount, command.note,
                )
                Transition(withStatus(ReconciliationCaseStatus.RESOLVED, actions + line), emptyList())
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
                error("OPEN·INVESTIGATING은 감사 기록에만 남고 저장된 case의 상태가 아니다 caseId=${id.value} status=$status")
            // 이 상태로 들어가는 전이가 아직 없다. 자동 조치 규칙을 만드는 PR이 이 분기의 처리를 정한다.
            ReconciliationCaseStatus.AUTO_REPAIRED ->
                error("AUTO_REPAIRED로 가는 전이는 아직 없다 caseId=${id.value}")
        }

    private fun resolvedDecision(): ReconciliationDecision =
        checkNotNull(actions.last { it.status == ReconciliationCaseStatus.RESOLVED }.decision) {
            "RESOLVED 감사 줄은 결론을 가진다 caseId=${id.value}"
        }

    private fun requireValidResolution(command: ReconciliationCommand.Resolve) {
        if (command.note.isBlank() || command.note.length > MAX_NOTE_LENGTH) {
            throw invalidResolution("사유는 비어 있지 않고 ${MAX_NOTE_LENGTH}자 이하여야 합니다")
        }
        if ((command.providerReference?.length ?: 0) > MAX_PROVIDER_REFERENCE_LENGTH) {
            throw invalidResolution("PG 승인 참조는 ${MAX_PROVIDER_REFERENCE_LENGTH}자 이하여야 합니다")
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
            ReconciliationDecision.DECLINED ->
                if (command.approvedAmount != null) throw invalidResolution("거절에는 승인 금액을 실을 수 없습니다")
        }
    }

    private fun invalidResolution(message: String) =
        DomainException(INVALID_RECONCILIATION_RESOLUTION, message, DomainErrorKind.INVALID)

    private fun withStatus(status: ReconciliationCaseStatus, actions: List<ReconciliationCaseAction>) =
        ReconciliationCase(
            id, paymentId, orderId, storeId, terminalId, checkoutNo, reason, status, ruleVersion, amount,
            unknownReason, queryCount, reportedAmount, reportedProviderReference, actions,
        )

    override fun equals(other: Any?) = other is ReconciliationCase && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val RECONCILIATION_CASE_NOT_FOUND = "RECONCILIATION_CASE_NOT_FOUND"
        const val RECONCILIATION_CASE_ALREADY_RESOLVED = "RECONCILIATION_CASE_ALREADY_RESOLVED"
        const val RECONCILIATION_PAYMENT_ALREADY_CONCLUDED = "RECONCILIATION_PAYMENT_ALREADY_CONCLUDED"
        const val INVALID_RECONCILIATION_RESOLUTION = "INVALID_RECONCILIATION_RESOLUTION"
        const val RECONCILIATION_AMOUNT_MISMATCH = "RECONCILIATION_AMOUNT_MISMATCH"

        /** 분류 규칙 버전. 규칙을 바꾸면 올려서, 옛 case가 왜 그 상태로 갔는지 남긴다. */
        const val RULE_VERSION = 1

        /** 컬럼 `t_reconciliation_case_action.note varchar(500)`을 따라간다. 컬럼을 바꾸면 함께 바꾼다. */
        const val MAX_NOTE_LENGTH = 500

        /** 컬럼 `provider_reference varchar(255)`(`t_reconciliation_case_action`, `t_payment_attempt`)를 따라간다. 넘으면 커밋에서 실패해 500이 된다. */
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
            actions: List<ReconciliationCaseAction>,
        ): ReconciliationCase =
            ReconciliationCase(
                id, paymentId, orderId, storeId, terminalId, checkoutNo, reason, status, ruleVersion, amount,
                unknownReason, queryCount, reportedAmount, reportedProviderReference, actions,
            )

        /**
         * 넘겨받은 시도로 case를 연다. `OPEN`(넘긴 시각) → `INVESTIGATING`(규칙 평가) → 분류 결과 상태를 감사 줄 세 개로 남기고 최종 상태로 만든다.
         * 이벤트는 없다. 분류는 사유에 대한 else 없는 `when`이라 사유가 늘면 분류를 정하지 않고는 컴파일되지 않는다.
         */
        fun open(command: ReconciliationCommand.Open, ids: IdentifierGenerator, now: Instant): Transition<ReconciliationCase, Nothing> {
            val caseId = ReconciliationCaseId(ids.next())
            val classified = classify(command.reason)
            val actions = listOf(
                systemLine(ids, ReconciliationCaseStatus.OPEN, command.occurredAt),
                systemLine(ids, ReconciliationCaseStatus.INVESTIGATING, now),
                systemLine(ids, classified, now),
            )
            val opened = ReconciliationCase(
                caseId, command.paymentId, command.orderId, command.storeId, command.terminalId, command.checkoutNo, command.reason,
                classified, RULE_VERSION, command.amount, command.unknownReason, command.queryCount,
                command.reportedAmount, command.reportedProviderReference, actions,
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

        private fun systemLine(ids: IdentifierGenerator, status: ReconciliationCaseStatus, at: Instant) =
            ReconciliationCaseAction(ReconciliationCaseActionId(ids.next()), status, ReconciliationActorKind.SYSTEM, null, at)
    }
}
