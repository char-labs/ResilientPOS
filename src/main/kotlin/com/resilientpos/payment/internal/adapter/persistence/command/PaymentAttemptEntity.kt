package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.contract.PaymentUnknownReason
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
 * 역할: provider에게 보낸 요청 1회(승인 또는 취소·환불)와 그 결말(`REQUESTED`→`APPROVED`/`DECLINED`/`UNKNOWN`). 결제의 정본이다.
 * 관계: `orderId`·`checkoutNo`로 어느 주문의 몇 회차인지, `retriedFrom`으로 재결제의 원래 시도(같은 표)를 가리킨다. `storeId`·`terminalId`는 이벤트에서 받아 복사한다.
 * 흐름: `OrderPaymentRequested` consumer가 만들고(`REQUESTED`, 0원이면 바로 `APPROVED`), provider 호출은 트랜잭션 밖이며, 응답 뒤 행을 잠그고 결말을 적용하면 `PaymentApproved`가 주문을 `PAID`로 넘긴다. 거절 뒤 재결제는 새 행이다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `PaymentAttempt`의 JPA 매핑. 연관관계 매핑 없이 `orderId` 컬럼만 가진 독립 엔티티다.
 * 한 주문에 이 행이 여럿 달린다. 식별은 `id` 하나가 하고 `orderId`는 참조라서 개수에 제약이 없다.
 *
 * 통화를 `t_order`에서 읽지 않고 직접 가진다. `payment`는 `order`의 테이블을 읽지 않는다는 것이
 * 모듈 경계이고, 승인 금액을 검증하려면 통화가 금액과 같은 행에 있어야 한다.
 *
 * 두 enum은 `EnumType.STRING`이다. 기본값인 ORDINAL로 두면 값을 하나 끼워 넣는 순간
 * 이미 저장된 행의 뜻이 통째로 바뀐다.
 *
 * `retriedFrom`은 재결제로 만든 시도가 가리키는 원래 시도다. 사실로 만든 시도는 null이다.
 *
 * 바뀌는 것은 결말뿐이다. [applyResult]만 값을 바꾸고 나머지는 시도가 만들어질 때 확정된다.
 * 전 필드는 `var`에 `protected set`이다. `val`로 두지 않는 이유는 `OrderEntity`의 KDoc에 있다. 생성은 `from`만 한다(도메인 규칙 §5).
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
    retriedFrom: UUID?,
    unknownReason: PaymentUnknownReason?,
) : BaseEntity() {

    @Id
    var id: UUID = id
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
    var kind: PaymentAttemptKind = kind
        protected set

    var amount: BigDecimal = amount
        protected set

    @Column(columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    var currency: String = currency
        protected set

    @Enumerated(EnumType.STRING)
    var status: PaymentAttemptStatus = status
        protected set

    var providerReference: String? = providerReference
        protected set

    var retriedFrom: UUID? = retriedFrom
        protected set

    @Enumerated(EnumType.STRING)
    var unknownReason: PaymentUnknownReason? = unknownReason
        protected set

    /**
     * provider 결과를 반영한다. 바꿀 수 있는 것이 이 셋뿐이라는 사실을 시그니처로 말한다.
     * 무엇으로 바꿀 수 있는지는 aggregate의 `approve`·`decline`·`markUnknown`이 정하고, 여기는 그 결과를 옮기기만 한다.
     */
    fun applyResult(status: PaymentAttemptStatus, providerReference: String?, unknownReason: PaymentUnknownReason?) {
        this.status = status
        this.providerReference = providerReference
        this.unknownReason = unknownReason
    }

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
        retriedFrom = retriedFrom?.let(::PaymentId),
        // 롤링 배포 중 이전 버전이 만든 UNKNOWN 행은 사유가 null이다. 그 버전에는 금액 대조가 없어 불명 사유가 이것 하나였다.
        unknownReason = unknownReason ?: PaymentUnknownReason.RESULT_UNCONFIRMED.takeIf { status == PaymentAttemptStatus.UNKNOWN },
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
            retriedFrom = attempt.retriedFrom?.value,
            unknownReason = attempt.unknownReason,
        )
    }
}
