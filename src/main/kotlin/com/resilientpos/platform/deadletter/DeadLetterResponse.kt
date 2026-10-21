package com.resilientpos.platform.deadletter

import java.time.Instant

/**
 * DLT 레코드 한 건. 원 위치와 예외는 `DeadLetterPublishingRecoverer`가 붙인 헤더에서 읽고, 그 헤더가 없으면 null이다.
 * 스택 트레이스 헤더는 싣지 않는다. 레코드마다 수 KB라 목록이 커진다. [redrive]는 이 레코드의 재투입 기록이고 없으면 null이다.
 */
data class DeadLetterResponse(
    val partition: Int,
    val offset: Long,
    val timestamp: Instant,
    val key: String?,
    val value: String?,
    val originalTopic: String?,
    val originalPartition: Int?,
    val originalOffset: Long?,
    val exception: String?,
    val exceptionMessage: String?,
    val schemaVersion: Int?,
    val redrive: DeadLetterRedriveSummary?,
)
