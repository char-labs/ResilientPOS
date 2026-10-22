package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainException
import com.resilientpos.platform.DependencyUnavailableException
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.Properties
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * 설정된 DLT(`resilientpos.kafka.topics`의 각 이름 + `.DLT`)의 한 partition을 offset 범위로 읽는다. 다른 토픽은 읽지 않는다.
 *
 * Boot의 `ConsumerFactory`로 consumer를 만들어 assign·seek로 읽는다. group을 정하지 않고 자동 커밋을 끄므로 어떤 consumer group의 위치도 바뀌지 않는다.
 * 끝 offset을 먼저 읽어 그 범위만 poll한다. `KafkaTemplate.receive`는 레코드마다 poll해서 없는 offset마다 시간 초과까지 기다리므로 쓰지 않는다.
 * 브로커 호출은 각각 [KAFKA_TIMEOUT] 안에 끝나야 하고, 넘으면 [DependencyUnavailableException](503)이다.
 * 목록 항목의 재투입·폐기 기록은 그 쪽의 offset 범위로 `t_dead_letter_redrive`·`t_dead_letter_dismissal`을 한 번씩 조회해 레코드 시각까지 맞는 것만 붙인다. 재투입([readRecord])도 같은 위치 검증과 시간 상한으로 읽는다.
 */
@Component
class DeadLetterReader(
    private val consumers: ConsumerFactory<String, ByteArray>,
    private val jsonMapper: JsonMapper,
    private val redrives: DeadLetterRedriveJpaRepository,
    private val dismissals: DeadLetterDismissalJpaRepository,
    @Value("\${resilientpos.kafka.topics}") topics: List<String>,
) {
    private val deadLetterTopics = topics.map { it + DLT_SUFFIX }.toSet()

    fun read(topic: String, partition: Int, fromOffset: Long, limit: Int): List<DeadLetterResponse> {
        requirePosition(topic, fromOffset)
        if (limit !in 1..MAX_LIMIT) throw invalidPosition("limit은 1에서 $MAX_LIMIT 사이여야 합니다.")
        val records = readRecords(TopicPartition(topic, partition), fromOffset, limit)
        if (records.isEmpty()) return emptyList()
        val first = records.first().offset()
        val last = records.last().offset()
        val redriven = redrives.findByDltTopicAndDltPartitionAndDltOffsetBetween(topic, partition, first, last).associateBy { it.dltOffset to it.dltTimestamp }
        val dismissed = dismissals.findByDltTopicAndDltPartitionAndDltOffsetBetween(topic, partition, first, last).associateBy { it.dltOffset to it.dltTimestamp }
        return records.map {
            val source = it.offset() to Instant.ofEpochMilli(it.timestamp())
            toResponse(it, redriven[source], dismissed[source])
        }
    }

    /** 재투입할 레코드 하나. 위치 검증과 시간 상한은 목록과 같고, 그 offset에 레코드가 없으면(끝 이상, log 시작 앞) null이다. */
    fun readRecord(topic: String, partition: Int, offset: Long): ConsumerRecord<String, ByteArray>? {
        requirePosition(topic, offset)
        return readRecords(TopicPartition(topic, partition), offset, 1).singleOrNull()?.takeIf { it.offset() == offset }
    }

    private fun requirePosition(topic: String, offset: Long) {
        if (topic !in deadLetterTopics) throw invalidPosition("설정된 DLT 토픽이 아닙니다.")
        if (offset < 0) throw invalidPosition("offset은 0 이상이어야 합니다.")
    }

    private fun readRecords(partition: TopicPartition, fromOffset: Long, limit: Int): List<ConsumerRecord<String, ByteArray>> {
        try {
            // ponytail: use의 close()는 fetch session을 닫느라 최대 30초 기다린다. 레코드를 받은 뒤 브로커가 끊기면 응답이 그만큼 늦는다.
            // 운영자가 드물게 부르는 조회라 감수한다. 이 경로가 잦아지면 close(CloseOptions.timeout(KAFKA_TIMEOUT))로 줄인다.
            return consumers.createConsumer(null, null, null, NO_AUTO_COMMIT).use { it.readRange(partition, fromOffset, limit) }
        } catch (e: TimeoutException) {
            throw DependencyUnavailableException("kafka broker", e)
        }
    }

    private fun Consumer<String, ByteArray>.readRange(partition: TopicPartition, fromOffset: Long, limit: Int): List<ConsumerRecord<String, ByteArray>> {
        if (partitionsFor(partition.topic(), KAFKA_TIMEOUT).none { it.partition() == partition.partition() }) {
            throw invalidPosition("그 토픽에 없는 partition입니다.")
        }
        val partitions = listOf(partition)
        assign(partitions)
        val end = endOffsets(partitions, KAFKA_TIMEOUT).getValue(partition)
        val start = maxOf(fromOffset, beginningOffsets(partitions, KAFKA_TIMEOUT).getValue(partition))
        if (start >= end) return emptyList()
        // start + limit은 start가 Long 끝 근처면 넘친다. 남은 건수와 비교한다.
        val stop = if (end - start > limit) start + limit else end
        seek(partition, start)
        val records = mutableListOf<ConsumerRecord<String, ByteArray>>()
        val deadline = System.nanoTime() + KAFKA_TIMEOUT.toNanos()
        while (position(partition, KAFKA_TIMEOUT) < stop) {
            if (System.nanoTime() >= deadline) throw TimeoutException("$KAFKA_TIMEOUT 안에 offset $stop 까지 읽지 못했다")
            records += poll(POLL_INTERVAL).records(partition).filter { it.offset() < stop }
        }
        return records
    }

    private fun toResponse(record: ConsumerRecord<String, ByteArray>, redrive: DeadLetterRedriveEntity?, dismissal: DeadLetterDismissalEntity?): DeadLetterResponse {
        fun header(name: String): ByteArray? = record.headers().lastHeader(name)?.value()
        fun text(name: String): String? = header(name)?.toString(Charsets.UTF_8)
        return DeadLetterResponse(
            partition = record.partition(),
            offset = record.offset(),
            timestamp = Instant.ofEpochMilli(record.timestamp()),
            key = record.key(),
            value = record.value()?.toString(Charsets.UTF_8),
            originalTopic = text(KafkaHeaders.DLT_ORIGINAL_TOPIC),
            // DeadLetterPublishingRecoverer는 partition을 4바이트 int, offset을 8바이트 long(big-endian)으로 쓴다.
            originalPartition = header(KafkaHeaders.DLT_ORIGINAL_PARTITION)?.takeIf { it.size == Int.SIZE_BYTES }?.let { ByteBuffer.wrap(it).int },
            originalOffset = header(KafkaHeaders.DLT_ORIGINAL_OFFSET)?.takeIf { it.size == Long.SIZE_BYTES }?.let { ByteBuffer.wrap(it).long },
            exception = text(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN) ?: text(KafkaHeaders.DLT_EXCEPTION_FQCN),
            exceptionMessage = text(KafkaHeaders.DLT_EXCEPTION_MESSAGE),
            schemaVersion = record.value()?.let(::schemaVersion),
            redrive = redrive?.let(DeadLetterRedriveSummary::from),
            dismissal = dismissal?.let(DeadLetterDismissalSummary::from),
        )
    }

    /** 값이 JSON이 아니거나(DLT로 간 이유일 수 있다) `schemaVersion`이 정수가 아니면 null이다. */
    private fun schemaVersion(value: ByteArray): Int? =
        try {
            jsonMapper.readTree(value).get("schemaVersion")?.takeIf { it.isInt }?.intValue()
        } catch (e: JacksonException) {
            null
        }

    private fun invalidPosition(message: String) = DomainException(INVALID_DEAD_LETTER_POSITION, message)

    companion object {
        const val INVALID_DEAD_LETTER_POSITION = "INVALID_DEAD_LETTER_POSITION"
        private const val MAX_LIMIT = 50
        private const val DLT_SUFFIX = ".DLT"
        private val KAFKA_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val POLL_INTERVAL: Duration = Duration.ofMillis(200)

        /** DLT 이름에서 원 토픽을 얻는다. `KafkaConfiguration`의 DLT 목적지(`원 토픽 + .DLT`)의 역이다. */
        fun originalTopic(deadLetterTopic: String): String = deadLetterTopic.removeSuffix(DLT_SUFFIX)

        /** 설정에 group이 생겨도 이 consumer가 offset을 커밋하지 않게 한다. */
        private val NO_AUTO_COMMIT = Properties().apply { put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false) }
    }
}
