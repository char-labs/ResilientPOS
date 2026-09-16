package com.resilientpos.foundation

import java.time.Instant
import java.util.UUID

/**
 * 이미 일어난 사실 한 가지를 나타내는 도메인 이벤트의 공통 계약이다.
 */
interface DomainEvent {
    val eventId: UUID
    val occurredAt: Instant
}

/**
 * 상태 전이로 바뀐 aggregate와 그 결과로 발생한 이벤트 목록을 함께 담는 값이다.
 */
data class Transition<A, E : DomainEvent>(val aggregate: A, val events: List<E>)
