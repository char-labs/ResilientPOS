package com.resilientpos.platform.deadletter

import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 역할: 플랫폼 운영자의 DLT 재투입 한 건. 감사의 정본이자 멱등 키다. 어느 DLT 레코드(`dlt*`)를 원 토픽의 어디에(`target*`) 누가(`operatorSubject`, 토큰의 `sub`) 왜(`reason`) 넣었는지.
 * 관계: unique `ux_dead_letter_redrive_source(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 한 DLT 레코드(좌표와 레코드 시각)의 기록을 하나로 묶는다. 원 토픽은 `dltTopic`에서 `.DLT`를 뺀 값, 재투입 시각은 `createdAt`이다.
 * 흐름: 재투입이 브로커 ack 뒤에 한 번 넣고 바꾸거나 지우지 않는다. 변경 메서드가 없고 행 갱신이 없어 `@Version`을 두지 않는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * 업무 aggregate가 없는 운영 기록이라 생성은 값을 그대로 받는 [of]다. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 */
@Entity
@Table(name = "t_dead_letter_redrive")
class DeadLetterRedriveEntity private constructor(
    id: UUID,
    dltTopic: String,
    dltPartition: Int,
    dltOffset: Long,
    dltTimestamp: Instant,
    targetPartition: Int,
    targetOffset: Long,
    operatorSubject: String,
    reason: String,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var dltTopic: String = dltTopic
        protected set

    var dltPartition: Int = dltPartition
        protected set

    var dltOffset: Long = dltOffset
        protected set

    /** DLT 레코드의 timestamp. DLT 토픽을 다시 만들어 offset이 재사용돼도 옛 기록과 가른다. */
    var dltTimestamp: Instant = dltTimestamp
        protected set

    var targetPartition: Int = targetPartition
        protected set

    var targetOffset: Long = targetOffset
        protected set

    var operatorSubject: String = operatorSubject
        protected set

    var reason: String = reason
        protected set

    companion object {
        fun of(
            id: UUID,
            dltTopic: String,
            dltPartition: Int,
            dltOffset: Long,
            dltTimestamp: Instant,
            targetPartition: Int,
            targetOffset: Long,
            operatorSubject: String,
            reason: String,
        ): DeadLetterRedriveEntity = DeadLetterRedriveEntity(id, dltTopic, dltPartition, dltOffset, dltTimestamp, targetPartition, targetOffset, operatorSubject, reason)
    }
}
