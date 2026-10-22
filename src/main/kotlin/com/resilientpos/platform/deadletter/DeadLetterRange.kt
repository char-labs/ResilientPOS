package com.resilientpos.platform.deadletter

import org.apache.kafka.common.TopicPartition

/** DLT 파티션 하나의 offset 범위. [from]은 포함, [until]은 미포함이다(Kafka의 earliest, latest 그대로). */
data class DeadLetterRange(val partition: TopicPartition, val from: Long, val until: Long)
