package com.resilientpos.platform.deadletter

import java.time.Instant

/** 목록 항목에 붙는 폐기 기록. 폐기한 운영자(`sub`)와 시각. */
data class DeadLetterDismissalSummary(
    val operator: String,
    val dismissedAt: Instant,
) {
    companion object {
        fun from(entity: DeadLetterDismissalEntity) = DeadLetterDismissalSummary(entity.operatorSubject, entity.createdAt)
    }
}
