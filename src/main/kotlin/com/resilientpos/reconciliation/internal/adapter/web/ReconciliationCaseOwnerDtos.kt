package com.resilientpos.reconciliation.internal.adapter.web

import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationCaseView
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.store.StoreId
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * `POST /owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve` 요청. 매장과 case는 경로로 온다.
 * [approvedAmount]는 [approvedCurrency] 기준 decimal string이고 둘은 함께만 온다. 사유 길이·참조 필수 같은 규칙은 case가 검사한다.
 */
data class ResolveReconciliationCaseRequest(
    val decision: ReconciliationDecision,
    val providerReference: String? = null,
    val approvedAmount: String? = null,
    val approvedCurrency: Currency? = null,
    val reason: String? = null,
) {
    fun toCommand(storeId: UUID, caseId: UUID, ownerId: UUID) = ReconciliationCommand.Resolve(
        caseId = ReconciliationCaseId(caseId),
        storeId = StoreId(storeId),
        ownerId = OwnerId(ownerId),
        decision = decision,
        providerReference = providerReference,
        approvedAmount = approvedMoney(),
        note = reason.orEmpty(),
    )

    private fun approvedMoney(): Money? {
        if (approvedAmount == null && approvedCurrency == null) return null
        if (approvedAmount == null || approvedCurrency == null) {
            throw DomainException(
                ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION,
                "승인 금액과 통화는 함께 보내야 합니다",
                DomainErrorKind.INVALID,
            )
        }
        return Money.of(approvedAmount, approvedCurrency)
    }
}

data class ResolveReconciliationCaseResponse(
    val caseId: UUID,
    val status: ReconciliationCaseStatus,
    val decision: ReconciliationDecision,
    val paymentId: UUID,
    val paymentStatus: PaymentAttemptStatus,
) {
    companion object {
        fun from(result: ReconciliationCommand.Resolve.Result) = ResolveReconciliationCaseResponse(
            caseId = result.caseId.value,
            status = result.status,
            decision = result.decision,
            paymentId = result.paymentId.value,
            paymentStatus = result.paymentStatus,
        )
    }
}

/** 목록 항목. 금액은 통화 기준 decimal string과 통화 코드다. */
data class ReconciliationCaseResponse(
    val caseId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val terminalId: UUID,
    val checkoutNo: Int,
    val reason: PaymentReconciliationReason,
    val unknownReason: PaymentUnknownReason,
    val status: ReconciliationCaseStatus,
    val ruleVersion: Int,
    val amount: String,
    val currency: String,
    val queryCount: Int,
    val reportedAmount: String?,
    val reportedCurrency: String?,
    val reportedProviderReference: String?,
    val openedAt: Instant,
) {
    companion object {
        fun from(view: ReconciliationCaseView) = ReconciliationCaseResponse(
            caseId = view.caseId.value,
            paymentId = view.paymentId.value,
            orderId = view.orderId,
            terminalId = view.terminalId.value,
            checkoutNo = view.checkoutNo,
            reason = view.reason,
            unknownReason = view.unknownReason,
            status = view.status,
            ruleVersion = view.ruleVersion,
            amount = view.amount.amount.toPlainString(),
            currency = view.amount.currency.currencyCode,
            queryCount = view.queryCount,
            reportedAmount = view.reportedAmount?.amount?.toPlainString(),
            reportedCurrency = view.reportedAmount?.currency?.currencyCode,
            reportedProviderReference = view.reportedProviderReference,
            openedAt = view.openedAt,
        )
    }
}
