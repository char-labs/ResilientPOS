package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.transaction.support.TransactionOperations

/**
 * 통합 테스트는 DB 하나를 나눠 쓰므로 다른 테스트가 남긴 기록도 세어진다.
 * 테스트마다 고유한 리스너 이름으로 행을 넣고 그 태그만 본다.
 */
@Import(EventPublicationMetricsTest.ThreadProbeListener::class)
class EventPublicationMetricsTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val metrics: EventPublicationMetrics,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
) : PostgresIntegrationTest() {

    /** 가짜 이벤트 타입의 미완료 행이 남으면 종료 때 Modulith가 복원하다 실패한다. 같은 DB를 쓰는 다른 컨텍스트에도 번진다. */
    @AfterTest
    fun cleanUp() {
        jdbcTemplate.update("delete from event_publication where event_type = 'com.example.Probe'")
    }

    private fun uniqueListener(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun insert(listener: String, status: String, age: Duration, completed: Boolean = false): UUID {
        val id = UUID.randomUUID()
        val publishedAt = Instant.now().minus(age)
        jdbcTemplate.update(
            """
            insert into event_publication
                (id, listener_id, event_type, serialized_event, publication_date, completion_date, status, completion_attempts)
            values (?, ?, 'com.example.Probe', '{}', ?, ?, ?, 0)
            """.trimIndent(),
            id, "com.example.internal.$listener.on(com.example.Probe)", Timestamp.from(publishedAt),
            if (completed) Timestamp.from(Instant.now()) else null, status,
        )
        return id
    }

    private fun gauges(listener: String): Map<String, Double> =
        registry.find("event.publication.incomplete").tag("listener", listener).gauges()
            .associate { it.id.getTag("status")!! to it.value() }

    @Test
    fun `리스너별로 실패한 기록과 기준 시간이 지난 미완료 기록을 센다`() {
        val a = uniqueListener("ProbeA")
        val b = uniqueListener("ProbeB")
        insert(a, "FAILED", Duration.ofSeconds(1))
        insert(a, "PUBLISHED", Duration.ofMinutes(6))
        insert(b, "PROCESSING", Duration.ofMinutes(6))
        insert(a, "PUBLISHED", Duration.ofMinutes(1))
        insert(a, "COMPLETED", Duration.ofMinutes(6), completed = true)

        metrics.refresh()

        assertEquals(mapOf("failed" to 1.0, "stale" to 1.0), gauges(a))
        assertEquals(mapOf("stale" to 1.0), gauges(b))
    }

    @Test
    fun `기록이 사라지면 그 조합의 시계열도 없어진다`() {
        val a = uniqueListener("ProbeGone")
        val id = insert(a, "FAILED", Duration.ofMinutes(10))
        metrics.refresh()
        assertEquals(mapOf("failed" to 1.0), gauges(a))

        jdbcTemplate.update("delete from event_publication where id = ?", id)
        metrics.refresh()

        assertEquals(emptyMap(), gauges(a))
    }

    @Test
    fun `listener 태그는 listener_id의 클래스 단순 이름이다`() {
        assertEquals(
            "OrderPaidListener",
            EventPublicationMetrics.listenerName(
                "com.resilientpos.fulfillment.internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)",
            ),
        )
    }

    /** 스케줄링을 켜면 TaskScheduler가 생긴다. 그래도 커밋 뒤 리스너는 Boot의 task executor에서 돌아야 한다. */
    @Test
    fun `스케줄링을 켜도 커밋 뒤 리스너는 task executor에서 돈다`() {
        transactions.executeWithoutResult { eventPublisher.publishEvent(ThreadProbe(UUID.randomUUID())) }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (probeThread.get() == null) {
            if (System.nanoTime() >= deadline) fail("10초 안에 리스너가 돌지 않았다")
            Thread.sleep(20)
        }
        assertTrue(probeThread.get().startsWith("task-"), "리스너 스레드: ${probeThread.get()}")
    }

    data class ThreadProbe(val id: UUID)

    /** 프록시가 필드를 가리므로 결과를 companion에 둔다. */
    @TestConfiguration
    class ThreadProbeListener {
        @ApplicationModuleListener
        fun on(event: ThreadProbe) {
            probeThread.compareAndSet(null, Thread.currentThread().name)
        }
    }

    companion object {
        private val probeThread = AtomicReference<String>()
    }
}
