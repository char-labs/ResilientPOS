package com.resilientpos.platform.deadletter

import java.time.Instant
import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `t_dead_letter_dismissal` 조회. 조건 몇 개뿐이라 파생 쿼리로 둔다(도메인 모델 §4.3). 둘 다 unique `ux_dead_letter_dismissal_source`의 인덱스를 탄다.
 * 파생 쿼리는 트랜잭션을 선언하지 않아 호출자 트랜잭션을 따르고, 없으면 쿼리 하나만 돌고 EntityManager를 놓는다.
 * 새 행은 [DeadLetterDismisser]가 `EntityManager.persist`로 넣는다(`@Version`이 없어 `save`는 `merge`로 가 SELECT가 더 나간다).
 *
 * `JpaRepository`를 상속하지 않는다. 기록은 멱등 키라 지우지 않고, `deleteAllInBatch` 같은 대량 삭제가 soft delete와 만나 모든 행이 지워지면 폐기 기록이 통째로 조회에서 사라진다.
 */
interface DeadLetterDismissalJpaRepository : Repository<DeadLetterDismissalEntity, UUID> {

    /** 한 DLT 레코드(좌표와 레코드 시각)의 기록. */
    fun findByDltTopicAndDltPartitionAndDltOffsetAndDltTimestamp(dltTopic: String, dltPartition: Int, dltOffset: Long, dltTimestamp: Instant): DeadLetterDismissalEntity?

    /** 목록 한 쪽의 기록. [from]·[to]는 양끝을 포함한다. 같은 offset에 레코드 시각이 다른 옛 기록(DLT 토픽 재생성)도 오므로 호출자가 시각을 맞춘다. */
    fun findByDltTopicAndDltPartitionAndDltOffsetBetween(dltTopic: String, dltPartition: Int, from: Long, to: Long): List<DeadLetterDismissalEntity>
}
