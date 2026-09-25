package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import org.springframework.beans.factory.ObjectProvider
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
@Import(EventPublicationMetricsTest.FailingProbeListener::class)
class EventPublicationMetricsTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val metrics: EventPublicationMetrics,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    @Autowired private val dataSource: DataSource,
    @Autowired private val scheduledTasks: ObjectProvider<ScheduledTaskHolder>,
) : PostgresIntegrationTest() {

    /**
     * 이 클래스가 남긴 행을 지운다. 가짜 이벤트 타입의 미완료 행이 남으면 종료 때 Modulith가 복원하다 실패하고,
     * 실패 리스너의 `FAILED` 행은 같은 DB를 쓰는 다른 테스트에 오염된 기록으로 남는다.
     */
    @AfterTest
    fun cleanUp() {
        jdbcTemplate.update(
            "delete from event_publication where event_type = 'com.example.Probe' or event_type like ?",
            "${EventPublicationMetricsTest::class.java.name}$%",
        )
    }

    private fun uniqueListener(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun insert(listener: String, status: String?, age: Duration, resubmittedAge: Duration? = null): UUID {
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
        val c = uniqueListener("ProbeC")
        insert(a, "FAILED", Duration.ofSeconds(1))
        insert(a, "PUBLISHED", Duration.ofMinutes(6))
        insert(b, "PROCESSING", Duration.ofMinutes(6))
        insert(a, "PUBLISHED", Duration.ofMinutes(1))
        insert(a, "COMPLETED", Duration.ofMinutes(6))
        // Modulith의 실패 조회처럼 status가 비어 있는 미완료 행은 실패다. 모르는 상태값은 세지 않는다.
        insert(c, null, Duration.ofSeconds(1))
        insert(c, "UNKNOWN_STATUS", Duration.ofMinutes(6))

        metrics.refresh()

        assertEquals(mapOf("failed" to 1.0, "stale" to 1.0), gauges(a))
        assertEquals(mapOf("stale" to 1.0), gauges(b))
        assertEquals(mapOf("failed" to 1.0), gauges(c))
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
    fun `값만 바뀐 조합은 같은 gauge가 새 값을 읽는다`() {
        val a = uniqueListener("ProbeGrow")
        insert(a, "FAILED", Duration.ofMinutes(10))
        metrics.refresh()
        val before = registry.find("event.publication.incomplete").tag("listener", a).gauge()!!

        insert(a, "FAILED", Duration.ofMinutes(10))
        metrics.refresh()

        val after = registry.find("event.publication.incomplete").tag("listener", a).gauge()!!
        assertTrue(before === after, "같은 조합은 다시 등록하지 않는다")
        assertEquals(2.0, after.value())
    }

    @Test
    fun `module과 listener 태그는 listener_id에서 뽑는다`() {
        assertEquals(
            "OrderPaidListener",
            EventPublicationMetrics.listenerName(
                "com.resilientpos.fulfillment.internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)",
            ),
        )
        assertEquals(
            "fulfillment",
            EventPublicationMetrics.moduleName(
                "com.resilientpos.fulfillment.internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)",
            ),
        )
        assertEquals("unknown", EventPublicationMetrics.moduleName("com.example.internal.Probe.on(com.resilientpos.order.X)"))
        assertEquals("unknown", EventPublicationMetrics.moduleName("com.resilientpos.Probe.on(com.resilientpos.order.X)"))
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
        // 테스트 리스너는 com.resilientpos.platform 아래라 실제 Modulith 행의 모듈 값까지 고정된다.
        assertEquals(
            listOf("platform"),
            registry.find("event.publication.incomplete").tag("listener", "FailingProbeListener").gauges()
                .map { it.id.getTag("module") },
        )
    }

    /**
     * 스프링 빈이 아닌 인스턴스로 실패를 만든다. 조회가 실패한 인스턴스는 개수를 내리고 신선도 gauge를 멈춘다.
     * 낡은 개수가 남으면 `max`로 모은 알림이 이미 조치된 기록을 붙잡는다.
     */
    @Test
    fun `조회가 실패하면 개수를 내리고 마지막 갱신 시각을 멈췄다가 성공하면 되돌린다`() {
        val flaky = FlakyDataSource(dataSource)
        val clock = MutableClock(Instant.now())
        val own = SimpleMeterRegistry()
        val subject = EventPublicationMetrics(flaky, clock, own, Duration.ofMinutes(5))
        val a = uniqueListener("ProbeFlaky")
        insert(a, "FAILED", Duration.ofMinutes(10))
        subject.refresh()
        val refreshed = own.get("event.publication.incomplete.refreshed").gauge()
        assertEquals(clock.instant().epochSecond.toDouble(), refreshed.value())

        flaky.failing = true
        insert(a, "FAILED", Duration.ofMinutes(10))
        val before = clock.instant()
        clock.now = before.plus(Duration.ofMinutes(1))
        subject.refresh()

        assertTrue(own.find("event.publication.incomplete").tag("listener", a).gauges().isEmpty(), "개수가 남아 있다")
        assertEquals(before.epochSecond.toDouble(), refreshed.value())

        // 다음 조회가 성공하면 실패 중에 더해진 행까지 세고 갱신 시각도 돌아온다.
        flaky.failing = false
        subject.refresh()
        assertEquals(2.0, own.get("event.publication.incomplete").tag("listener", a).gauge().value())
        assertEquals(clock.instant().epochSecond.toDouble(), refreshed.value())
    }

    @Test
    fun `기준 시간이 0 이하면 기동을 막는다`() {
        for (staleAfter in listOf(Duration.ZERO, Duration.ofMinutes(-1))) {
            assertFailsWith<IllegalArgumentException>("$staleAfter") {
                EventPublicationMetrics(dataSource, Clock.systemUTC(), SimpleMeterRegistry(), staleAfter)
            }
        }
    }

    /** 공용 스케줄러는 스레드가 하나라 느린 조회가 Moments까지 붙잡는다. 갱신은 거기 올리지 않고 전용 스레드에서 돈다. */
    @Test
    fun `갱신은 공용 스케줄러가 아니라 전용 스레드에서 돈다`() {
        val tasks = scheduledTasks.flatMap { it.scheduledTasks }.map { it.task.toString() }
        assertTrue(tasks.none { EventPublicationMetrics::class.java.name in it }, "공용 스케줄러에 올라가 있다: $tasks")
        assertTrue(
            Thread.getAllStackTraces().keys.any { it.name == EventPublicationMetrics.THREAD_NAME && it.isAlive },
            "전용 갱신 스레드가 없다",
        )
    }

    data class FailingProbe(val id: UUID)

    @TestConfiguration
    class FailingProbeListener {
        @ApplicationModuleListener
        fun on(event: FailingProbe): Unit = throw IllegalStateException("의도한 실패")
    }

    /** 켜면 커넥션을 주지 않는다. `JdbcTemplate`이 `DataAccessException`으로 바꾼다. */
    class FlakyDataSource(private val delegate: DataSource) : DataSource by delegate {
        @Volatile var failing = false

        override fun getConnection(): Connection =
            if (failing) throw SQLException("의도한 조회 실패") else delegate.connection
    }

    class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
