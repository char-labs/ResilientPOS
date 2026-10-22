package com.resilientpos.platform.deadletter

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException as JavaTimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.DescribeTopicsOptions
import org.apache.kafka.clients.admin.ListOffsetsOptions
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 토픽마다 아직 재투입도 폐기도 하지 않은 DLT 레코드 수를 `event.kafka.dead-letter.unhandled{topic}`으로 낸다. `topic`은 원 토픽이다(`.DLT` 없이).
 * 알림은 이 값이 0보다 큰 동안 울리고, 운영자가 재투입하거나 폐기하면 풀린다.
 *
 * 계산: DLT(`토픽.DLT`)의 파티션마다 `latest - earliest`(레코드 수)에서, 그 offset 범위의 재투입·폐기 기록의 합집합 수([DeadLetterHandledQueries])를 빼고
 * 0 아래면 0으로 둔다. 토픽별로 더한다. DLT는 보존 기간이 없고 compaction·트랜잭션 producer도 없어 offset에 빈자리가 없다.
 * 모든 토픽을 계산한 뒤에 한꺼번에 값을 바꾼다. 중간에 실패하면 아무것도 바꾸지 않는다.
 *
 * Kafka는 [Admin] 하나를 빈 수명 동안 쓴다. `KafkaAdmin.describeTopics` 같은 메서드는 호출마다 AdminClient를 만들고 닫아 1분마다 `AdminClientConfig values` INFO 블록을 남기고,
 * consumer도 만들 때마다 `ConsumerConfig values` 블록을 남긴다. 한 갱신의 브로커 호출 전체는 [KAFKA_TIMEOUT] 안에 끝나야 한다.
 *
 * 주기는 공용 스케줄러의 1분이고 기동 직후 바로 한 번 돈다. 갱신 전에는 `NaN`이다. pod마다 같은 값을 내므로 규칙이 `max`로 묶는다.
 * 실패하면(브로커·DB·권한) 예외를 그대로 던져 작업이 `outcome=ERROR`로 남게 하고 값은 마지막 성공값을 유지한다.
 * 브로커에 닿지 못하는 동안에는 새 DLT도 쌓이지 않고 재투입·폐기도 DLT를 읽어야 해서 일어나지 않아 실제 값이 변하지 않기 때문이다.
 *
 * 한계: 범위와 좌표만 본다. DLT 토픽을 다시 만들어 offset이 겹치면 옛 기록이 새 레코드를 처리된 것으로 셀 수 있다.
 */
@Component
class UnhandledDeadLetterMetrics(
    kafkaAdmin: KafkaAdmin,
    registry: MeterRegistry,
    private val handled: DeadLetterHandledQueries,
    @Value("\${resilientpos.kafka.topics}") private val topics: List<String>,
) : DisposableBean {
    private val admin: Admin = Admin.create(kafkaAdmin.configurationProperties)
    private val values = topics.associateWith { AtomicReference(Double.NaN) }

    init {
        values.forEach { (topic, value) ->
            Gauge.builder("event.kafka.dead-letter.unhandled", value) { it.get() }
                .description("재투입도 폐기도 하지 않은 DLT 레코드 수")
                .tag("topic", topic).strongReference(true).register(registry)
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    fun countUnhandled() {
        val deadline = System.nanoTime() + KAFKA_TIMEOUT.toNanos()
        val partitions = describe(deadline)
        val earliest = offsets(partitions, OffsetSpec.earliest(), deadline)
        val latest = offsets(partitions, OffsetSpec.latest(), deadline)
        val ranges = partitions.map { DeadLetterRange(it, earliest.getValue(it), latest.getValue(it)) }.filter { it.until > it.from }
        val handledCounts = if (ranges.isEmpty()) emptyMap() else handled.count(ranges)
        val unhandled = topics.associateWith { topic ->
            ranges.filter { it.partition.topic() == topic + DLT_SUFFIX }
                .sumOf { maxOf(0L, it.until - it.from - (handledCounts[it.partition] ?: 0L)) }
        }
        unhandled.forEach { (topic, count) -> values.getValue(topic).set(count.toDouble()) }
    }

    private fun describe(deadline: Long): List<TopicPartition> {
        val described = admin.describeTopics(topics.map { it + DLT_SUFFIX }, DescribeTopicsOptions().timeoutMs(remainingMillis(deadline)))
            .allTopicNames().await(deadline)
        return described.values.flatMap { topic -> topic.partitions().map { TopicPartition(topic.name(), it.partition()) } }
    }

    private fun offsets(partitions: List<TopicPartition>, spec: OffsetSpec, deadline: Long): Map<TopicPartition, Long> =
        admin.listOffsets(partitions.associateWith { spec }, ListOffsetsOptions().timeoutMs(remainingMillis(deadline)))
            .all().await(deadline).mapValues { it.value.offset() }

    private fun remainingMillis(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1).toInt()

    /** Kafka 예외를 원인 그대로 던져 observation의 `exception` 태그가 원인 클래스가 되게 한다. */
    private fun <T> KafkaFuture<T>.await(deadline: Long): T {
        try {
            return get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: JavaTimeoutException) {
            throw TimeoutException("Kafka 브로커 호출이 ${KAFKA_TIMEOUT.seconds}초 안에 끝나지 않았다", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    override fun destroy() = admin.close(CLOSE_TIMEOUT)

    companion object {
        private const val DLT_SUFFIX = ".DLT"
        private val KAFKA_TIMEOUT = Duration.ofSeconds(5)
        private val CLOSE_TIMEOUT = Duration.ofSeconds(1)
    }
}
