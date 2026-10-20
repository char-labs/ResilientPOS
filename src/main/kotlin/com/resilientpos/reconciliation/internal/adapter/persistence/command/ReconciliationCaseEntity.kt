package com.resilientpos.reconciliation.internal.adapter.persistence.command

import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId
import com.resilientpos.platform.BaseEntity
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.reconciliation.internal.domain.ReconciliationCaseAction
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 역할: 조회로도 확정되지 않아 `payment`가 넘긴 시도를 점주가 근거와 함께 해소할 때까지 들고 있는 대조 case. 근거·분류 규칙 버전·상태가 이 행이다.
 * 관계: `paymentId`로 어느 시도의 case인지(`ux_reconciliation_case_payment`, 시도 하나에 case 하나), `storeId`로 어느 매장이 해소하는지 가리킨다. 근거 컬럼(금액·불명 사유·조회 횟수·알려 준 금액)은 넘긴 시점의 사본이다.
 * 흐름: `PaymentReconciliationRequested` consumer가 감사 세 줄과 함께 `MANUAL_REVIEW`로 만들고, 점주의 해소가 `RESOLVED`로 바꾼다. 감사 기록은 `ReconciliationCaseActionEntity`다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `ReconciliationCase`의 JPA 매핑. 연관관계 매핑 없이 감사 기록과는 `case_id` 컬럼으로만 이어지는 독립 엔티티다.
 * 바뀌는 것은 `status`뿐이다. [apply]만 값을 바꾸고 나머지는 case가 열릴 때 확정된다. `@Version`은 두지 않는다. 해소만 행을 바꾸고 그 경로는 row lock이 줄 세운다.
 * 전 필드는 `var`에 `protected set`이다. `val`로 두지 않는 이유는 `OrderEntity`의 KDoc에 있다. 생성은 `from`만 한다(도메인 규칙 §5).
 */
@Entity
@Table(name = "t_reconciliation_case")
class ReconciliationCaseEntity private constructor(
    id: UUID,
    paymentId: UUID,
    orderId: UUID,
    storeId: UUID,
    terminalId: UUID,
    checkoutNo: Int,
    reason: PaymentReconciliationReason,
    status: ReconciliationCaseStatus,
    ruleVersion: Int,
    amount: BigDecimal,
    currency: String,
    unknownReason: PaymentUnknownReason,
    queryCount: Int,
    reportedAmount: BigDecimal?,
    reportedCurrency: String?,
    reportedProviderReference: String?,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var paymentId: UUID = paymentId
        protected set

    var orderId: UUID = orderId
        protected set

    var storeId: UUID = storeId
        protected set

    var terminalId: UUID = terminalId
        protected set

    var checkoutNo: Int = checkoutNo
        protected set

    @Enumerated(EnumType.STRING)
    var reason: PaymentReconciliationReason = reason
        protected set

    @Enumerated(EnumType.STRING)
    var status: ReconciliationCaseStatus = status
        protected set

    var ruleVersion: Int = ruleVersion
        protected set

    var amount: BigDecimal = amount
        protected set

    @Column(columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    var currency: String = currency
        protected set

    @Enumerated(EnumType.STRING)
    var unknownReason: PaymentUnknownReason = unknownReason
        protected set

    var queryCount: Int = queryCount
        protected set

    var reportedAmount: BigDecimal? = reportedAmount
        protected set

    @Column(columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    var reportedCurrency: String? = reportedCurrency
        protected set

    var reportedProviderReference: String? = reportedProviderReference
        protected set

    /** 바꿀 수 있는 것은 상태뿐이다. 무엇으로 바꿀 수 있는지는 aggregate의 전이가 정하고, 여기는 결과를 옮기기만 한다. */
    fun apply(reconciliationCase: ReconciliationCase) {
        status = reconciliationCase.status
    }

    fun toDomain(actions: List<ReconciliationCaseAction>): ReconciliationCase {
        val currency = Currency.getInstance(currency)
        return ReconciliationCase.restore(
            id = ReconciliationCaseId(id),
            paymentId = PaymentId(paymentId),
            orderId = orderId,
            storeId = StoreId(storeId),
            terminalId = TerminalId(terminalId),
            checkoutNo = checkoutNo,
            reason = reason,
            status = status,
            ruleVersion = ruleVersion,
            amount = Money.of(amount, currency),
            unknownReason = unknownReason,
            queryCount = queryCount,
            reportedAmount = reportedAmount?.let { Money.of(it, Currency.getInstance(checkNotNull(reportedCurrency))) },
            reportedProviderReference = reportedProviderReference,
            actions = actions,
        )
    }

    companion object {
        fun from(reconciliationCase: ReconciliationCase): ReconciliationCaseEntity = ReconciliationCaseEntity(
            id = reconciliationCase.id.value,
            paymentId = reconciliationCase.paymentId.value,
            orderId = reconciliationCase.orderId,
            storeId = reconciliationCase.storeId.value,
            terminalId = reconciliationCase.terminalId.value,
            checkoutNo = reconciliationCase.checkoutNo,
            reason = reconciliationCase.reason,
            status = reconciliationCase.status,
            ruleVersion = reconciliationCase.ruleVersion,
            amount = reconciliationCase.amount.amount,
            currency = reconciliationCase.amount.currency.currencyCode,
            unknownReason = reconciliationCase.unknownReason,
            queryCount = reconciliationCase.queryCount,
            reportedAmount = reconciliationCase.reportedAmount?.amount,
            reportedCurrency = reconciliationCase.reportedAmount?.currency?.currencyCode,
            reportedProviderReference = reconciliationCase.reportedProviderReference,
        )
    }
}
