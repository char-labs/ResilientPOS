package com.resilientpos.platform.deadletter

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.platform.JdslQueryExecutor
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate

private const val TOPIC = "order.paid"
private const val DLT = "order.paid.DLT"
private const val OTHER_DLT = "payment.approved.DLT"
private const val OTHER_PARTITION = 1000
private const val OUT_OF_RANGE = 1_000_000L
private const val GAUGE = "event.kafka.dead-letter.unhandled"
private const val REFRESHED_GAUGE = "event.kafka.dead-letter.unhandled.refreshed"

/**
 * 미처리 DLT gauge를 실제 브로커·DB로 센다. Docker가 필요하다. DLT 토픽과 두 기록 표를 다른 테스트와 함께 쓰므로 증가분으로 단언하고, 넣은 기록 행은 끝에 지운다.
 */
class UnhandledDeadLetterMetricsIntegrationTest(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val kafkaAdmin: KafkaAdmin,
    private val queries: DeadLetterHandledQueries,
    private val jdsl: JdslQueryExecutor,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    @Value("\${resilientpos.kafka.topics}") private val topics: List<String>,
) : PostgresIntegrationTest() {

    private fun gauge(registry: MeterRegistry, topic: String = TOPIC) = registry.get(GAUGE).tag("topic", topic).gauge().value()

    /** DLT에 레코드 하나를 넣고 좌표와 레코드 시각(DLT 행의 `dlt_timestamp`)을 돌려준다. */
    private fun deadLetter(): Triple<Int, Long, Instant> {
        val sent = kafkaTemplate.send(DLT, UUID.randomUUID().toString(), "{}".toByteArray()).get().recordMetadata
        return Triple(sent.partition(), sent.offset(), Instant.ofEpochMilli(sent.timestamp()))
    }

    private fun redrive(record: Triple<Int, Long, Instant>, topic: String = DLT, partition: Int = record.first, offset: Long = record.second) = jdbcTemplate.update(
        "insert into t_dead_letter_redrive (id, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, target_partition, target_offset, operator_subject, reason, created_at, updated_at) " +
            "values (?, ?, ?, ?, ?, 0, 0, 'gauge-test', 'gauge-test', now(), now())",
        UUID.randomUUID(), topic, partition, offset, Timestamp.from(record.third),
    )

    private fun dismiss(record: Triple<Int, Long, Instant>, topic: String = DLT, partition: Int = record.first, offset: Long = record.second) = jdbcTemplate.update(
        "insert into t_dead_letter_dismissal (id, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, operator_subject, reason, created_at, updated_at) " +
            "values (?, ?, ?, ?, ?, 'gauge-test', 'gauge-test', now(), now())",
        UUID.randomUUID(), topic, partition, offset, Timestamp.from(record.third),
    )

    @Test
    fun `DLT 레코드는 재투입이나 폐기 기록이 있으면 하나씩 빠지고 둘 다 있어도 한 번만 빠진다`() {
        // 스케줄러가 도는 빈은 1분 주기 실행이 값을 덮을 수 있어 새 인스턴스와 registry로 센다.
        val fresh = SimpleMeterRegistry()
        val instance = UnhandledDeadLetterMetrics(kafkaAdmin, fresh, queries, clock, topics)
        fun counted(): Double {
            instance.countUnhandled()
            return gauge(fresh)
        }
        val base = counted()
        assertFalse(base.isNaN(), "갱신이 한 번 성공해야 기준값이 있다")
        val records = List(3) { deadLetter() }
        try {
            assertEquals(base + 3, counted(), "새 DLT 레코드 3개")

            redrive(records[0])
            assertEquals(base + 2, counted(), "재투입 기록 하나")

            dismiss(records[1])
            assertEquals(base + 1, counted(), "폐기 기록 하나")

            redrive(records[2])
            dismiss(records[2])
            assertEquals(base, counted(), "둘 다 있는 레코드는 한 번만 뺀다")
        } finally {
            instance.destroy()
            cleanUp(records.map { it.second })
        }
    }

    @Test
    fun `다른 partition이나 범위 밖 offset이나 다른 DLT 토픽의 기록 행은 미처리 수에서 빼지 않는다`() {
        val fresh = SimpleMeterRegistry()
        val instance = UnhandledDeadLetterMetrics(kafkaAdmin, fresh, queries, clock, topics)
        val record = deadLetter()
        val strayOffsets = listOf(record.second, record.second + OUT_OF_RANGE, record.second + OUT_OF_RANGE + 1)
        try {
            instance.countUnhandled()
            val before = gauge(fresh)
            assertTrue(before >= 1.0, "방금 넣은 레코드가 센다: $before")

            redrive(record, partition = record.first + OTHER_PARTITION)
            dismiss(record, partition = record.first + OTHER_PARTITION)
            redrive(record, offset = record.second + OUT_OF_RANGE)
            dismiss(record, offset = record.second + OUT_OF_RANGE)
            redrive(record, topic = OTHER_DLT, offset = record.second + OUT_OF_RANGE + 1)
            dismiss(record, topic = OTHER_DLT, offset = record.second + OUT_OF_RANGE + 1)

            instance.countUnhandled()
            assertEquals(before, gauge(fresh), "좌표나 범위가 다른 기록이 빠졌다")
        } finally {
            instance.destroy()
            cleanUp(strayOffsets)
        }
    }

    private fun cleanUp(offsets: List<Long>) = offsets.forEach { offset ->
        listOf("t_dead_letter_redrive", "t_dead_letter_dismissal").forEach {
            jdbcTemplate.update("delete from $it where dlt_offset = ? and dlt_topic in (?, ?)", offset, DLT, OTHER_DLT)
        }
    }

    @Test
    fun `첫 갱신 전에는 설정된 토픽마다 NaN이다`() {
        val fresh = SimpleMeterRegistry()
        UnhandledDeadLetterMetrics(kafkaAdmin, fresh, queries, clock, topics).destroy()

        assertTrue(topics.isNotEmpty())
        topics.forEach { assertTrue(gauge(fresh, it).isNaN(), "$it 는 NaN") }
    }

    @Test
    fun `갱신이 실패하면 예외를 던지고 마지막 성공값을 유지한다`() {
        val fresh = SimpleMeterRegistry()
        var failing = false
        val failingQueries = object : DeadLetterHandledQueries(jdsl) {
            override fun count(ranges: List<DeadLetterRange>): Map<TopicPartition, Long> {
                check(!failing) { "DB 조회 실패" }
                return super.count(ranges)
            }
        }
        val instance = UnhandledDeadLetterMetrics(kafkaAdmin, fresh, failingQueries, clock, topics)
        try {
            deadLetter()
            instance.countUnhandled()
            val last = gauge(fresh)
            assertTrue(last >= 1.0, "DLT 레코드가 있으면 미처리 수는 하나 이상이다: $last")

            deadLetter()
            failing = true
            assertFailsWith<IllegalStateException> { instance.countUnhandled() }
            assertEquals(last, gauge(fresh))
        } finally {
            instance.destroy()
        }
    }

    @Test
    fun `브로커에 닿지 못하면 상한 안에 Kafka 예외를 던지고 값은 NaN이다`() {
        val unreachable = KafkaAdmin(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:1"))
        val fresh = SimpleMeterRegistry()
        val untouchedQueries = object : DeadLetterHandledQueries(jdsl) {
            override fun count(ranges: List<DeadLetterRange>): Map<TopicPartition, Long> = error("브로커 실패면 DB를 조회하지 않는다")
        }
        val instance = UnhandledDeadLetterMetrics(unreachable, fresh, untouchedQueries, clock, topics)
        try {
            val started = System.nanoTime()
            assertFailsWith<TimeoutException> { instance.countUnhandled() }
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(8), "5초 상한을 넘었다")
            topics.forEach { assertTrue(gauge(fresh, it).isNaN()) }
        } finally {
            instance.destroy()
        }
    }

    @Test
    fun `마지막 성공 시각은 첫 갱신 전 0이고 성공하면 그 갱신의 시각이며 실패하면 그대로다`() {
        val fresh = SimpleMeterRegistry()
        val now = MutableClock(Instant.parse("2026-10-22T01:02:03Z"))
        var failing = false
        val failingQueries = object : DeadLetterHandledQueries(jdsl) {
            override fun count(ranges: List<DeadLetterRange>): Map<TopicPartition, Long> {
                check(!failing) { "DB 조회 실패" }
                return super.count(ranges)
            }
        }
        val instance = UnhandledDeadLetterMetrics(kafkaAdmin, fresh, failingQueries, now, topics)
        try {
            assertEquals(0.0, fresh.get(REFRESHED_GAUGE).gauge().value(), "첫 갱신 전")

            deadLetter()
            instance.countUnhandled()
            assertEquals(Instant.parse("2026-10-22T01:02:03Z").epochSecond.toDouble(), fresh.get(REFRESHED_GAUGE).gauge().value(), "성공한 갱신의 시각")

            now.now = Instant.parse("2026-10-22T01:03:04Z")
            failing = true
            assertFailsWith<IllegalStateException> { instance.countUnhandled() }
            assertEquals(Instant.parse("2026-10-22T01:02:03Z").epochSecond.toDouble(), fresh.get(REFRESHED_GAUGE).gauge().value(), "실패하면 그대로")

            failing = false
            instance.countUnhandled()
            assertEquals(Instant.parse("2026-10-22T01:03:04Z").epochSecond.toDouble(), fresh.get(REFRESHED_GAUGE).gauge().value(), "다시 성공하면 새 시각")
        } finally {
            instance.destroy()
        }
    }

    private class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
