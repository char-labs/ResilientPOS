package com.resilientpos.reconciliation.internal.adapter.persistence.query

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationCaseView
import com.resilientpos.reconciliation.internal.adapter.persistence.command.ReconciliationCaseActionEntity
import com.resilientpos.reconciliation.internal.adapter.persistence.command.ReconciliationCaseEntity
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import org.springframework.stereotype.Component

/**
 * 대조 case 조회 구현. aggregate를 거치지 않고 엔티티를 바로 [ReconciliationCaseView]로 바꾼다.
 * 구현이 하나뿐이고 View는 aggregate 경계에 들어가지 않아서 port 인터페이스를 두지 않는다.
 */
@Component
class ReconciliationCaseQueries(
    private val queries: JdslQueryExecutor,
) {

    /**
     * 열린 시각은 case를 여는 감사 줄(`OPEN`)의 `occurredAt`이라 감사 표와 `case_id`로 조인해 한 쿼리로 읽는다(case마다 `OPEN` 줄은 하나다).
     * 목록은 `ix_reconciliation_case_store (store_id, status, id)`를 타고 감사 줄은 `ix_reconciliation_case_action_case (case_id, id)`를 탄다.
     */
    @ReadOnlyTransactional
    fun findReviewable(storeId: StoreId): List<ReconciliationCaseView> =
        queries.findAll(REVIEWABLE_LIMIT) {
            val case = entity(ReconciliationCaseEntity::class)
            val opened = entity(ReconciliationCaseActionEntity::class)
            selectNew<OpenedCase>(case, opened(ReconciliationCaseActionEntity::occurredAt))
                .from(case, join(opened).on(opened(ReconciliationCaseActionEntity::caseId).eq(case(ReconciliationCaseEntity::id))))
                .where(
                    and(
                        case(ReconciliationCaseEntity::storeId).eq(storeId.value),
                        case(ReconciliationCaseEntity::status).eq(ReconciliationCaseStatus.MANUAL_REVIEW),
                        opened(ReconciliationCaseActionEntity::status).eq(ReconciliationCaseStatus.OPEN),
                    ),
                )
                .orderBy(case(ReconciliationCaseEntity::id).desc())
        }.map { it.toView() }

    companion object {
        const val REVIEWABLE_LIMIT = 100
    }
}

/** 조인 결과 한 행. case와 그 `OPEN` 감사 줄의 시각이다. */
private data class OpenedCase(val case: ReconciliationCaseEntity, val openedAt: Instant)

private fun OpenedCase.toView() = ReconciliationCaseView(
    caseId = ReconciliationCaseId(case.id),
    paymentId = PaymentId(case.paymentId),
    orderId = case.orderId,
    terminalId = TerminalId(case.terminalId),
    checkoutNo = case.checkoutNo,
    reason = case.reason,
    unknownReason = case.unknownReason,
    status = case.status,
    ruleVersion = case.ruleVersion,
    amount = Money.of(case.amount, Currency.getInstance(case.currency)),
    queryCount = case.queryCount,
    reportedAmount = case.reportedAmount?.let { Money.of(it, Currency.getInstance(checkNotNull(case.reportedCurrency))) },
    reportedProviderReference = case.reportedProviderReference,
    openedAt = openedAt,
)
