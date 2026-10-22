package com.resilientpos.platform.deadletter

import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import org.apache.kafka.common.TopicPartition
import org.springframework.stereotype.Component

/**
 * 재투입 기록과 폐기 기록의 합집합을 DLT 파티션 범위별로 센다. 둘 다 있는 레코드는 한 번 센다. 레코드가 없는 파티션은 키가 없을 수 있다. 갱신 한 번에 조회 둘이다.
 * 하나는 범위 안의 재투입 기록, 다른 하나는 같은 레코드(좌표와 레코드 시각)의 재투입 기록이 없는 폐기 기록이다. 둘 다 있는 레코드는 앞쪽에서만 센다.
 * 둘 다 unique 인덱스 `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`를 탄다. 트랜잭션 없이 호출하면 쿼리마다 따로 읽는다.
 */
@Component
class DeadLetterHandledQueries(private val queries: JdslQueryExecutor) {

    private data class Row(val topic: String, val partition: Int, val count: Long)

    @ReadOnlyTransactional
    fun count(ranges: List<DeadLetterRange>): Map<TopicPartition, Long> {
        val redriven = queries.findAll {
            selectNew<Row>(path(DeadLetterRedriveEntity::dltTopic), path(DeadLetterRedriveEntity::dltPartition), count(DeadLetterRedriveEntity::id))
                .from(entity(DeadLetterRedriveEntity::class))
                .where(
                    or(
                        *ranges.map {
                            and(
                                path(DeadLetterRedriveEntity::dltTopic).eq(it.partition.topic()),
                                path(DeadLetterRedriveEntity::dltPartition).eq(it.partition.partition()),
                                path(DeadLetterRedriveEntity::dltOffset).between(it.from, it.until - 1),
                            )
                        }.toTypedArray(),
                    ),
                )
                .groupBy(path(DeadLetterRedriveEntity::dltTopic), path(DeadLetterRedriveEntity::dltPartition))
        }
        val dismissedOnly = queries.findAll {
            val sameRecordRedriven = select(path(DeadLetterRedriveEntity::id))
                .from(entity(DeadLetterRedriveEntity::class))
                .where(
                    and(
                        path(DeadLetterRedriveEntity::dltTopic).eq(path(DeadLetterDismissalEntity::dltTopic)),
                        path(DeadLetterRedriveEntity::dltPartition).eq(path(DeadLetterDismissalEntity::dltPartition)),
                        path(DeadLetterRedriveEntity::dltOffset).eq(path(DeadLetterDismissalEntity::dltOffset)),
                        path(DeadLetterRedriveEntity::dltTimestamp).eq(path(DeadLetterDismissalEntity::dltTimestamp)),
                    ),
                )
                .asSubquery()
            selectNew<Row>(path(DeadLetterDismissalEntity::dltTopic), path(DeadLetterDismissalEntity::dltPartition), count(DeadLetterDismissalEntity::id))
                .from(entity(DeadLetterDismissalEntity::class))
                .where(
                    and(
                        or(
                            *ranges.map {
                                and(
                                    path(DeadLetterDismissalEntity::dltTopic).eq(it.partition.topic()),
                                    path(DeadLetterDismissalEntity::dltPartition).eq(it.partition.partition()),
                                    path(DeadLetterDismissalEntity::dltOffset).between(it.from, it.until - 1),
                                )
                            }.toTypedArray(),
                        ),
                        not(exists(sameRecordRedriven)),
                    ),
                )
                .groupBy(path(DeadLetterDismissalEntity::dltTopic), path(DeadLetterDismissalEntity::dltPartition))
        }
        return (redriven + dismissedOnly).groupBy({ TopicPartition(it.topic, it.partition) }, { it.count }).mapValues { it.value.sum() }
    }
}
