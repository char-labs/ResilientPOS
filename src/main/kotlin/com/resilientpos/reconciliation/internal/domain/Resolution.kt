package com.resilientpos.reconciliation.internal.domain

import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.reconciliation.ReconciliationDecision
import java.time.Instant

/**
 * 점주가 case를 해소한 기록: 누가([ownerId]) 언제([resolvedAt]) 무엇으로([decision]·[providerReference]·[approvedAmount]) 왜([note]) 했는지.
 * 식별자 없는 값 객체이고 case당 하나다. 해소 전의 case는 이 값이 없다. [providerReference]·[approvedAmount]는 거절이면 null일 수 있다.
 */
data class Resolution(
    val ownerId: OwnerId,
    val resolvedAt: Instant,
    val decision: ReconciliationDecision,
    val providerReference: String?,
    val approvedAmount: Money?,
    val note: String,
)
