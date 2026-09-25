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
import kotlin.test.BeforeTest
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
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.transaction.support.TransactionOperations

/**
 * 통합 테스트는 DB 하나를 나눠 쓰므로 다른 테스트가 남긴 기록도 세어진다.
 * 테스트마다 고유한 리스너 이름으로 행을 넣고 그 태그만 본다.
 * 1분 주기의 스케줄 갱신도 같은 컨텍스트에서 돈다. `refresh()`가 직렬화되어 있어 늦게 끝난 쪽이 늘 최신 조회다.
 * 테스트 전용 리스너는 중첩 `@TestConfiguration`이라 자동 감지되지만 등록 여부가 읽히도록 명시한다.
 */
@Import(EventPublicationMetricsTest.ThreadProbeListener::class, EventPublicationMetricsTest.FailingProbeListener::class)
class EventPublicationMetricsTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val metrics: EventPublicationMetrics,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    @Autowired private val scheduledTasks: List<ScheduledTaskHolder>,
) : PostgresIntegrationTest() {

    /**
     * 이 클래스가 남긴 행을 지운다. 가짜 이벤트 타입의 미완료 행이 남으면 종료 때 Modulith가 복원하다 실패하고,
     * 실패 리스너의 `FAILED` 행은 같은 DB를 쓰는 다른 테스트에 오염된 기록으로 남는다.
     */
    @BeforeTest
    fun resetProbe() {
        probeThread.set(null)
    }

    @AfterTest
    fun cleanUp() {
        jdbcTemplate.update(
            "delete from event_publication where event_type = 'com.example.Probe' or event_type like ?",
            "${EventPublicationMetricsTest::class.java.name}$%",
        )
    }

    private fun uniqueListener(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun insert(listener: String, status: String, age: Duration, resubmittedAge: Duration? = null): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbcTemplate.update(
            """
            insert into event_publication
                (id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                 completion_attempts, last_resubmission_date)
            values (?, ?, 'com.example.Probe', '{}', ?, ?, ?, 0, ?)
            """.trimIndent(),
            id, "com.example.internal.$listener.on(com.example.Probe)", Timestamp.from(now.minus(age)),
            if (status == "COMPLETED") Timestamp.from(now) else null, status,
            resubmittedAge?.let { Timestamp.from(now.minus(it)) },
        )
        return id
    }

    private fun gauges(listener: String): Map<String, Double> =
        registry.find("event.publication.incomplete").tag("listener", listener).gauges()
            .associate { it.id.getTag("status")!! to it.value() }

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("10초 안에 충족되지 않았다: $description")
            Thread.sleep(20)
        }
    }

    @Test
    fun `리스너별로 실패한 기록과 기준 시간이 지난 미완료 기록을 센다`() {
        val a = uniqueListener("ProbeA")
        val b = uniqueListener("ProbeB")
        insert(a, "FAILED", Duration.ofSeconds(1))
        insert(a, "PUBLISHED", Duration.ofMinutes(6))
        insert(b, "PROCESSING", Duration.ofMinutes(6))
        insert(a, "PUBLISHED", Duration.ofMinutes(1))
        insert(a, "COMPLETED", Duration.ofMinutes(6))

        metrics.refresh()

        assertEquals(mapOf("failed" to 1.0, "stale" to 1.0), gauges(a))
        assertEquals(mapOf("stale" to 1.0), gauges(b))
    }

    /** 오래된 이벤트를 방금 다시 보냈다면 아직 처리 중일 수 있다. 발행 시각이 아니라 마지막 재전송 시각부터 잰다. */
    @Test
    fun `재전송된 기록은 마지막 재전송 시각부터 잰다`() {
        val a = uniqueListener("ProbeResubmitted")
        insert(a, "RESUBMITTED", Duration.ofMinutes(30), resubmittedAge = Duration.ofMinutes(1))
        insert(a, "RESUBMITTED", Duration.ofMinutes(30), resubmittedAge = Duration.ofMinutes(6))

        metrics.refresh()

        assertEquals(mapOf("stale" to 1.0), gauges(a))
    }

    @Test
    fun `기록이 완료되거나 사라지면 그 조합의 시계열도 없어진다`() {
        val completed = uniqueListener("ProbeCompleted")
        val deleted = uniqueListener("ProbeDeleted")
        val completedId = insert(completed, "FAILED", Duration.ofMinutes(10))
        val deletedId = insert(deleted, "FAILED", Duration.ofMinutes(10))
        metrics.refresh()
        assertEquals(mapOf("failed" to 1.0), gauges(completed))
        assertEquals(mapOf("failed" to 1.0), gauges(deleted))

        jdbcTemplate.update(
            "update event_publication set completion_date = now(), status = 'COMPLETED' where id = ?", completedId,
        )
        jdbcTemplate.update("delete from event_publication where id = ?", deletedId)
        metrics.refresh()

        assertEquals(emptyMap(), gauges(completed))
        assertEquals(emptyMap(), gauges(deleted))
    }

    /** 값만 바뀐 조합은 같은 gauge가 새 값을 읽는다. 지웠다 다시 등록하면 그 틈의 scrape에서 시계열이 빠진다. */
    @Test
    fun `값만 바뀐 조합은 같은 gauge가 새 값을 읽고 갱신 시각이 남는다`() {
        val a = uniqueListener("ProbeGrow")
        insert(a, "FAILED", Duration.ofMinutes(10))
        metrics.refresh()
        val before = registry.find("event.publication.incomplete").tag("listener", a).gauge()!!

        insert(a, "FAILED", Duration.ofMinutes(10))
        metrics.refresh()

        val after = registry.find("event.publication.incomplete").tag("listener", a).gauge()!!
        assertTrue(before === after, "같은 조합은 다시 등록하지 않는다")
        assertEquals(2.0, after.value())
        val refreshed = registry.get("event.publication.incomplete.refreshed").gauge().value()
        assertTrue(Instant.now().epochSecond - refreshed < 60, "마지막 갱신 시각: $refreshed")
    }

    @Test
    fun `listener 태그는 listener_id의 클래스 단순 이름이다`() {
        assertEquals(
            "OrderPaidListener",
            EventPublicationMetrics.listenerName(
                "com.resilientpos.fulfillment.internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)",
            ),
        )
        assertEquals(
            "Inner",
            EventPublicationMetrics.listenerName("com.example.Outer\$Inner.on(com.example.Outer\$Event)"),
        )
    }

    /** SQL로 넣은 행이 아니라 Modulith가 실제로 남긴 실패 기록의 모양(status, completion_date)을 세는지 본다. */
    @Test
    fun `리스너가 던지면 Modulith가 남긴 기록을 failed로 센다`() {
        transactions.executeWithoutResult { eventPublisher.publishEvent(FailingProbe(UUID.randomUUID())) }

        awaitUntil("실패 기록이 남는다") {
            jdbcTemplate.queryForObject(
                "select count(*) from event_publication where listener_id like ? and status = 'FAILED'",
                Int::class.java, "%FailingProbeListener%",
            ) == 1
        }
        metrics.refresh()

        assertEquals(mapOf("failed" to 1.0), gauges("FailingProbeListener"))
    }

    /** 다른 테스트는 `refresh()`를 직접 부른다. 1분 주기 갱신이 실제로 등록됐는지는 여기서 본다. */
    @Test
    fun `갱신이 스케줄에 등록된다`() {
        val tasks = scheduledTasks.flatMap { it.scheduledTasks }.map { it.task.toString() }
        assertTrue("${EventPublicationMetrics::class.java.name}.refresh" in tasks, "등록된 스케줄: $tasks")
    }

    /** 스케줄링을 켜면 TaskScheduler가 생긴다. 그래도 커밋 뒤 리스너는 Boot의 task executor에서 돌아야 한다. */
    @Test
    fun `스케줄링을 켜도 커밋 뒤 리스너는 task executor에서 돈다`() {
        transactions.executeWithoutResult { eventPublisher.publishEvent(ThreadProbe(UUID.randomUUID())) }

        awaitUntil("리스너가 돈다") { probeThread.get() != null }
        assertTrue(probeThread.get().startsWith("task-"), "리스너 스레드: ${probeThread.get()}")
    }

    data class ThreadProbe(val id: UUID)

    data class FailingProbe(val id: UUID)

    /** 프록시가 필드를 가리므로 결과를 companion에 둔다. */
    @TestConfiguration
    class ThreadProbeListener {
        @ApplicationModuleListener
        fun on(event: ThreadProbe) {
            probeThread.compareAndSet(null, Thread.currentThread().name)
        }
    }

    @TestConfiguration
    class FailingProbeListener {
        @ApplicationModuleListener
        fun on(event: FailingProbe): Unit = throw IllegalStateException("의도한 실패")
    }

    companion object {
        private val probeThread = AtomicReference<String>()
    }
}
