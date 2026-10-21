package com.resilientpos.reconciliation

import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * 대조 case의 읽기 투영. aggregate를 거치지 않고 저장된 행에서 바로 만든다.
 * 금액은 넘긴 시점의 사본이고, [openedAt]은 case를 연 시각(`opened_at`), 곧 `payment`가 넘긴 시각이다.
 */
data class ReconciliationCaseView(
    val caseId: ReconciliationCaseId,
    val paymentId: PaymentId,
    val orderId: UUID,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val reason: PaymentReconciliationReason,
    val unknownReason: PaymentUnknownReason,
    val status: ReconciliationCaseStatus,
    val ruleVersion: Int,
    val amount: Money,
    val queryCount: Int,
    val reportedAmount: Money?,
    val reportedProviderReference: String?,
    val openedAt: Instant,
)
