package com.resilientpos.reconciliation.internal.adapter.persistence.query

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationCaseView
import com.resilientpos.reconciliation.internal.adapter.persistence.command.ReconciliationCaseEntity
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
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

    /** 열린 시각은 `opened_at`(넘긴 시각)이라 한 쿼리, 조인 없이 읽는다. 목록은 `ix_reconciliation_case_store (store_id, status, id)`를 탄다. */
    @ReadOnlyTransactional
    fun findReviewable(storeId: StoreId): List<ReconciliationCaseView> =
        queries.findAll(REVIEWABLE_LIMIT) {
            selectFrom(ReconciliationCaseEntity::class)
                .where(
                    and(
                        path(ReconciliationCaseEntity::storeId).eq(storeId.value),
                        path(ReconciliationCaseEntity::status).eq(ReconciliationCaseStatus.MANUAL_REVIEW),
                    ),
                )
                .orderBy(path(ReconciliationCaseEntity::id).desc())
        }.map { it.toView() }

    companion object {
        const val REVIEWABLE_LIMIT = 100
    }
}

private fun ReconciliationCaseEntity.toView() = ReconciliationCaseView(
    caseId = ReconciliationCaseId(id),
    paymentId = PaymentId(paymentId),
    orderId = orderId,
    terminalId = TerminalId(terminalId),
    checkoutNo = checkoutNo,
    reason = reason,
    unknownReason = unknownReason,
    status = status,
    ruleVersion = ruleVersion,
    amount = Money.of(amount, Currency.getInstance(currency)),
    queryCount = queryCount,
    reportedAmount = reportedAmount?.let { Money.of(it, Currency.getInstance(checkNotNull(reportedCurrency))) },
    reportedProviderReference = reportedProviderReference,
    openedAt = openedAt,
)
