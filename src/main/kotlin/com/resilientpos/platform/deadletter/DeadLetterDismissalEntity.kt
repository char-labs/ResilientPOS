package com.resilientpos.platform.deadletter

import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 역할: 플랫폼 운영자의 DLT 폐기 한 건. 재투입하지 않기로 한 DLT 레코드(`dlt*`)를 누가(`operatorSubject`, 토큰의 `sub`) 왜(`reason`) 넣지 않았는지의 감사 정본이자 멱등 키다.
 * 관계: unique `ux_dead_letter_dismissal_source(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 한 DLT 레코드(좌표와 레코드 시각)의 기록을 하나로 묶는다. 폐기 시각은 `createdAt`이다.
 * 재투입 기록([DeadLetterRedriveEntity])과 서로 참조하지 않는다. 한 레코드에 둘 다 있을 수 있다.
 * 흐름: 폐기가 한 번 넣고 바꾸거나 지우지 않는다. 변경 메서드가 없고 행 갱신이 없어 `@Version`을 두지 않는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * 업무 aggregate가 없는 운영 기록이라 생성은 값을 그대로 받는 [of]다. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 */
@Entity
@Table(name = "t_dead_letter_dismissal")
class DeadLetterDismissalEntity private constructor(
    id: UUID,
    dltTopic: String,
    dltPartition: Int,
    dltOffset: Long,
    dltTimestamp: Instant,
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
            operatorSubject: String,
            reason: String,
        ): DeadLetterDismissalEntity = DeadLetterDismissalEntity(id, dltTopic, dltPartition, dltOffset, dltTimestamp, operatorSubject, reason)
    }
}
