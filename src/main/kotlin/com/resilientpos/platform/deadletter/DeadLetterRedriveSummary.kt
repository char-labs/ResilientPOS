package com.resilientpos.platform.deadletter

import java.time.Instant

/** 목록 항목에 붙는 재투입 기록. 원 토픽에 넣은 자리([partition]·[offset])와 운영자(`sub`), 시각. */
data class DeadLetterRedriveSummary(
    val partition: Int,
    val offset: Long,
    val operator: String,
    val redrivenAt: Instant,
) {
    companion object {
        fun from(entity: DeadLetterRedriveEntity) =
            DeadLetterRedriveSummary(entity.targetPartition, entity.targetOffset, entity.operatorSubject, entity.createdAt)
    }
}
