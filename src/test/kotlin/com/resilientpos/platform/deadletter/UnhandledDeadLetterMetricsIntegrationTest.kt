package com.resilientpos.platform.deadletter

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.platform.JdslQueryExecutor
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate

private const val TOPIC = "order.paid"
private const val DLT = "order.paid.DLT"
private const val GAUGE = "event.kafka.dead-letter.unhandled"

/**
 * 미처리 DLT gauge를 실제 브로커·DB로 센다. Docker가 필요하다. DLT 토픽과 두 기록 표를 다른 테스트와 함께 쓰므로 증가분으로 단언하고, 넣은 기록 행은 끝에 지운다.
 */
class UnhandledDeadLetterMetricsIntegrationTest(
    @Autowired private val metrics: UnhandledDeadLetterMetrics,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Autowired private val kafkaAdmin: KafkaAdmin,
    @Autowired private val queries: DeadLetterHandledQueries,
    @Autowired private val jdsl: JdslQueryExecutor,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Value("\${resilientpos.kafka.topics}") private val topics: List<String>,
) : PostgresIntegrationTest() {

    private fun gauge(registry: MeterRegistry, topic: String = TOPIC) = registry.get(GAUGE).tag("topic", topic).gauge().value()

    private fun refreshed(): Double {
        metrics.countUnhandled()
        return gauge(registry)
    }

    /** DLT에 레코드 하나를 넣고 좌표와 레코드 시각(DLT 행의 `dlt_timestamp`)을 돌려준다. */
    private fun deadLetter(): Triple<Int, Long, Instant> {
        val sent = kafkaTemplate.send(DLT, UUID.randomUUID().toString(), "{}".toByteArray()).get().recordMetadata
        return Triple(sent.partition(), sent.offset(), Instant.ofEpochMilli(sent.timestamp()))
    }

    private fun redrive(record: Triple<Int, Long, Instant>) = jdbcTemplate.update(
        "insert into t_dead_letter_redrive (id, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, target_partition, target_offset, operator_subject, reason, created_at, updated_at) " +
            "values (?, ?, ?, ?, ?, 0, 0, 'gauge-test', 'gauge-test', now(), now())",
        UUID.randomUUID(), DLT, record.first, record.second, Timestamp.from(record.third),
    )

    private fun dismiss(record: Triple<Int, Long, Instant>) = jdbcTemplate.update(
        "insert into t_dead_letter_dismissal (id, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, operator_subject, reason, created_at, updated_at) " +
            "values (?, ?, ?, ?, ?, 'gauge-test', 'gauge-test', now(), now())",
        UUID.randomUUID(), DLT, record.first, record.second, Timestamp.from(record.third),
    )

    @Test
    fun `DLT 레코드는 재투입이나 폐기 기록이 있으면 하나씩 빠지고 둘 다 있어도 한 번만 빠진다`() {
        val base = refreshed()
        assertFalse(base.isNaN(), "갱신이 한 번 성공해야 기준값이 있다")
        val records = List(3) { deadLetter() }
        try {
            assertEquals(base + 3, refreshed(), "새 DLT 레코드 3개")

            redrive(records[0])
            assertEquals(base + 2, refreshed(), "재투입 기록 하나")

            dismiss(records[1])
            assertEquals(base + 1, refreshed(), "폐기 기록 하나")

            redrive(records[2])
            dismiss(records[2])
            assertEquals(base, refreshed(), "둘 다 있는 레코드는 한 번만 뺀다")
        } finally {
            records.forEach { (_, offset, _) ->
                jdbcTemplate.update("delete from t_dead_letter_redrive where dlt_topic = ? and dlt_offset = ?", DLT, offset)
                jdbcTemplate.update("delete from t_dead_letter_dismissal where dlt_topic = ? and dlt_offset = ?", DLT, offset)
            }
        }
    }

    @Test
    fun `첫 갱신 전에는 설정된 토픽마다 NaN이다`() {
        val fresh = SimpleMeterRegistry()
        UnhandledDeadLetterMetrics(kafkaAdmin, fresh, queries, topics).destroy()

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
        val instance = UnhandledDeadLetterMetrics(kafkaAdmin, fresh, failingQueries, topics)
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
        val instance = UnhandledDeadLetterMetrics(unreachable, fresh, untouchedQueries, topics)
        try {
            val started = System.nanoTime()
            assertFailsWith<TimeoutException> { instance.countUnhandled() }
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(8), "5초 상한을 넘었다")
            topics.forEach { assertTrue(gauge(fresh, it).isNaN()) }
        } finally {
            instance.destroy()
        }
    }
}
