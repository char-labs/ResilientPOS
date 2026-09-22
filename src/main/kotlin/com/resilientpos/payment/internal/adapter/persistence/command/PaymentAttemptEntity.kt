package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.platform.BaseEntity
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
 * `PaymentAttempt`의 JPA 매핑. 연관관계 매핑 없이 `orderId` 컬럼만 가진 독립 엔티티다.
 * 한 주문에 이 행이 여럿 달린다. 식별은 `id` 하나가 하고 `orderId`는 참조라서 개수에 제약이 없다.
 *
 * 통화를 `t_order`에서 읽지 않고 직접 가진다. `payment`는 `order`의 테이블을 읽지 않는다는 것이
 * 모듈 경계이고, 승인 금액을 검증하려면 통화가 금액과 같은 행에 있어야 한다.
 *
 * 두 enum은 `EnumType.STRING`이다. 기본값인 ORDINAL로 두면 값을 하나 끼워 넣는 순간
 * 이미 저장된 행의 뜻이 통째로 바뀐다.
 *
 * 지금은 시도를 기록만 하므로 `apply`가 없고 전 필드가 `val`이다. 생성은 `from`만 한다(도메인 규칙 §5).
 * 승인 결과를 반영하는 명령이 생기면 그 PR이 `apply`를 더하면서 바뀌는 필드만 `var`에 `protected set`으로 되돌린다.
 */
@Entity
@Table(name = "t_payment_attempt")
class PaymentAttemptEntity private constructor(
    id: UUID,
    orderId: UUID,
    storeId: UUID,
    terminalId: UUID,
    checkoutNo: Int,
    kind: PaymentAttemptKind,
    amount: BigDecimal,
    currency: String,
    status: PaymentAttemptStatus,
    providerReference: String?,
) : BaseEntity() {

    @Id
    val id: UUID = id

    val orderId: UUID = orderId

    val storeId: UUID = storeId

    val terminalId: UUID = terminalId

    val checkoutNo: Int = checkoutNo

    @Enumerated(EnumType.STRING)
    val kind: PaymentAttemptKind = kind

    val amount: BigDecimal = amount

    @Column(columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    val currency: String = currency

    @Enumerated(EnumType.STRING)
    val status: PaymentAttemptStatus = status

    val providerReference: String? = providerReference

    fun toDomain(): PaymentAttempt = PaymentAttempt.restore(
        id = PaymentId(id),
        orderId = orderId,
        storeId = StoreId(storeId),
        terminalId = TerminalId(terminalId),
        checkoutNo = checkoutNo,
        kind = kind,
        amount = Money.of(amount, Currency.getInstance(currency)),
        status = status,
        providerReference = providerReference,
    )

    companion object {
        fun from(attempt: PaymentAttempt): PaymentAttemptEntity = PaymentAttemptEntity(
            id = attempt.id.value,
            orderId = attempt.orderId,
            storeId = attempt.storeId.value,
            terminalId = attempt.terminalId.value,
            checkoutNo = attempt.checkoutNo,
            kind = attempt.kind,
            amount = attempt.amount.amount,
            currency = attempt.amount.currency.currencyCode,
            status = attempt.status,
            providerReference = attempt.providerReference,
        )
    }
}
