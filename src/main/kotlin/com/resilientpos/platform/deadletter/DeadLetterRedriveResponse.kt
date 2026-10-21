package com.resilientpos.platform.deadletter

import java.time.Instant

/** 재투입 기록 한 건. 어느 DLT 레코드(`dlt*`)를 원 토픽([topic])의 어디에([partition]·[offset]) 넣었는지와 운영자(`sub`), 시각. */
data class DeadLetterRedriveResponse(
    val dltTopic: String,
    val dltPartition: Int,
    val dltOffset: Long,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val operator: String,
    val redrivenAt: Instant,
) {
    companion object {
        fun from(entity: DeadLetterRedriveEntity) = DeadLetterRedriveResponse(
            dltTopic = entity.dltTopic,
            dltPartition = entity.dltPartition,
            dltOffset = entity.dltOffset,
            topic = DeadLetterReader.originalTopic(entity.dltTopic),
            partition = entity.targetPartition,
            offset = entity.targetOffset,
            operator = entity.operatorSubject,
            redrivenAt = entity.createdAt,
        )
    }
}
