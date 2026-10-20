package com.resilientpos.reconciliation.internal.domain

import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationDecision
import java.time.Instant

/**
 * case 감사 기록 한 줄. 상태 전이마다 하나이고 넣기만 하며 바꾸지 않는다.
 * [status]는 전이한 **뒤**의 상태다. [decision]·[providerReference]·[approvedAmount]·[note]는 해소 줄에만 있다.
 */
data class ReconciliationCaseAction(
    val id: ReconciliationCaseActionId,
    val status: ReconciliationCaseStatus,
    val actorKind: ReconciliationActorKind,
    val ownerId: OwnerId?,
    val occurredAt: Instant,
    val decision: ReconciliationDecision? = null,
    val providerReference: String? = null,
    val approvedAmount: Money? = null,
    val note: String? = null,
)
