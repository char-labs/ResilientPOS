package com.resilientpos.platform.deadletter

import java.time.Instant

/** 폐기 기록 한 건. 어느 DLT 레코드(`dlt*`)를 누가(`sub`) 언제 폐기했는지. */
data class DeadLetterDismissResponse(
    val dltTopic: String,
    val dltPartition: Int,
    val dltOffset: Long,
    val operator: String,
    val dismissedAt: Instant,
) {
    companion object {
        fun from(entity: DeadLetterDismissalEntity) = DeadLetterDismissResponse(
            dltTopic = entity.dltTopic,
            dltPartition = entity.dltPartition,
            dltOffset = entity.dltOffset,
            operator = entity.operatorSubject,
            dismissedAt = entity.createdAt,
        )
    }
}
