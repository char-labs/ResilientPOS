package com.resilientpos.reconciliation.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.BaseEntity
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.internal.domain.ReconciliationActorKind
import com.resilientpos.reconciliation.internal.domain.ReconciliationCaseAction
import com.resilientpos.reconciliation.internal.domain.ReconciliationCaseActionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 역할: case가 상태를 바꿀 때마다 누가 언제 무엇을 했는지 한 줄씩 남기는 감사 기록. 해소 줄에는 결론·PG 참조·승인 금액·사유가 더 있다.
 * 관계: `caseId`로 `ReconciliationCaseEntity`를 가리킨다. 점주가 한 줄이면 `ownerId`는 그 점주이고 시스템이면 null이다.
 * 흐름: case를 열 때 세 줄, 해소할 때 한 줄이 루트와 한 커밋으로 생긴다. 넣기만 하고 바꾸지 않는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `ReconciliationCaseAction`의 JPA 매핑. 연관관계 매핑 없이 `caseId` 컬럼만 가진 독립 엔티티다.
 * 바꾸는 메서드가 없고 [from]으로만 만든다. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc과 도메인 규칙 §5).
 */
@Entity
@Table(name = "t_reconciliation_case_action")
class ReconciliationCaseActionEntity private constructor(
    id: UUID,
    caseId: UUID,
    status: ReconciliationCaseStatus,
    actorKind: ReconciliationActorKind,
    ownerId: UUID?,
    decision: ReconciliationDecision?,
    providerReference: String?,
    approvedAmount: BigDecimal?,
    approvedCurrency: String?,
    note: String?,
    occurredAt: Instant,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var caseId: UUID = caseId
        protected set

    @Enumerated(EnumType.STRING)
    var status: ReconciliationCaseStatus = status
        protected set

    @Enumerated(EnumType.STRING)
    var actorKind: ReconciliationActorKind = actorKind
        protected set

    var ownerId: UUID? = ownerId
        protected set

    @Enumerated(EnumType.STRING)
    var decision: ReconciliationDecision? = decision
        protected set

    var providerReference: String? = providerReference
        protected set

    var approvedAmount: BigDecimal? = approvedAmount
        protected set

    @Column(columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    var approvedCurrency: String? = approvedCurrency
        protected set

    var note: String? = note
        protected set

    var occurredAt: Instant = occurredAt
        protected set

    fun toDomain(): ReconciliationCaseAction = ReconciliationCaseAction(
        id = ReconciliationCaseActionId(id),
        status = status,
        actorKind = actorKind,
        ownerId = ownerId?.let(::OwnerId),
        occurredAt = occurredAt,
        decision = decision,
        providerReference = providerReference,
        approvedAmount = approvedAmount?.let { Money.of(it, Currency.getInstance(checkNotNull(approvedCurrency))) },
        note = note,
    )

    companion object {
        fun from(caseId: UUID, action: ReconciliationCaseAction): ReconciliationCaseActionEntity = ReconciliationCaseActionEntity(
            id = action.id.value,
            caseId = caseId,
            status = action.status,
            actorKind = action.actorKind,
            ownerId = action.ownerId?.value,
            decision = action.decision,
            providerReference = action.providerReference,
            approvedAmount = action.approvedAmount?.amount,
            approvedCurrency = action.approvedAmount?.currency?.currencyCode,
            note = action.note,
            occurredAt = action.occurredAt,
        )
    }
}
