package com.resilientpos.platform

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.stat.EntityStatistics
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.scheduling.config.ScheduledTaskHolder

/**
 * 주기 작업의 본문을 직접 부른다. 테스트 컨텍스트는 주기를 하루로 늘려(`PostgresIntegrationTest`) 작업이 스스로 돌지 않는다.
 * DB를 다른 테스트와 함께 쓰므로 이 클래스가 넣은 가짜 행은 끝나면 지운다. 작업은 다른 테스트가 남긴 `FAILED` 행도
 * 다시 보내므로 단언은 이 테스트의 행(주문 ID·가짜 이벤트 타입)만 본다.
 */
class EventPublicationResubmitterIntegrationTest(
    @Autowired private val resubmitter: EventPublicationResubmitter,
    @Autowired private val failed: FailedEventPublications,
    @Autowired private val completed: CompletedEventPublications,
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val dataSource: DataSource,
    @Autowired private val distributedLock: DistributedLock,
    @Autowired private val scheduledTasks: ObjectProvider<ScheduledTaskHolder>,
    @Autowired entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    /** Modulith의 발행 기록 엔티티만 센다. 백그라운드 consumer가 다른 엔티티를 읽고 쓰는 것과 섞이지 않게 한다. */
    private val publicationStatistics: EntityStatistics
        get() {
            val names: Array<String> = statistics.entityNames
            return statistics.getEntityStatistics(names.single { it.endsWith(".DefaultJpaEventPublication") })
        }

    private var statisticsWereEnabled = false

    @BeforeTest
    fun enableStatistics() {
        statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun cleanUp() {
        statistics.isStatisticsEnabled = statisticsWereEnabled
        jdbcTemplate.update("delete from event_publication where event_type = 'com.example.Probe'")
    }

    /** 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다. fulfillment까지 끝나면 `order.paid` 발행 기록도 완료돼 있다. */
    private fun paidOrder(): UUID {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("4500", OrderFixtures.krw), 1)),
            ),
        )
        orderFacade.requestCheckout(OrderCommand.RequestCheckout(created.orderId, OrderFixtures.storeId, OrderFixtures.terminalId))
        val orderId = created.orderId.value
        awaitUntil("order.paid 발행 기록이 완료된다") { findOrderPaidRow(orderId)?.get("completion_date") != null }
        return orderId
    }

    private fun findOrderPaidRow(orderId: UUID): Map<String, Any?>? =
        jdbcTemplate.queryForList(
            "select id, status, completion_date, completion_attempts from event_publication where event_type like ? and serialized_event like ?",
            "%.OrderPaid", "%$orderId%",
        ).singleOrNull()

    private fun orderPaidRow(orderId: UUID): Map<String, Any?> =
        checkNotNull(findOrderPaidRow(orderId)) { "order.paid 발행 기록이 없다: $orderId" }

    /** 브로커 전송이 실패한 상태로 되돌린다. */
    private fun failOrderPaid(orderId: UUID) =
        jdbcTemplate.update(
            "update event_publication set status = 'FAILED', completion_date = null where id = ?",
            orderPaidRow(orderId)["id"],
        )

    @Test
    fun `브로커 전송이 실패한 기록을 다시 보내 토픽에 도착하고 완료된다`() {
        val orderId = paidOrder()
        failOrderPaid(orderId)
        val attempts = orderPaidRow(orderId)["completion_attempts"] as Int

        resubmitter.resubmitAndPurge()

        awaitUntil("다시 보낸 기록이 완료된다") { orderPaidRow(orderId)["completion_date"] != null }
        assertEquals(2, KafkaTestSupport.records("order.paid", orderId).size, "처음 전송과 재전송")
        assertEquals(attempts + 1, orderPaidRow(orderId)["completion_attempts"], "재전송 표시가 한 번")
    }

    @Test
    fun `읽어 온 발행 기록 엔티티를 flush로 고쳐 쓰지 않는다`() {
        val orderId = paidOrder()
        failOrderPaid(orderId)
        val loaded = publicationStatistics.loadCount
        val updated = publicationStatistics.updateCount

        resubmitter.resubmitAndPurge()

        assertTrue(publicationStatistics.loadCount > loaded, "발행 기록 엔티티를 읽었다")
        assertEquals(updated, publicationStatistics.updateCount, "dirty checking UPDATE")
        // 비동기 전송이 다음 테스트로 새지 않게 끝을 기다린다.
        awaitUntil("다시 보낸 기록이 완료된다") { orderPaidRow(orderId)["completion_date"] != null }
    }

    @Test
    fun `보존 기간이 지난 완료 기록만 지운다`() {
        val old = insertRow("COMPLETED", publishedAgo = Duration.ofDays(9), completedAgo = Duration.ofDays(8))
        val recent = insertRow("COMPLETED", publishedAgo = Duration.ofDays(9), completedAgo = Duration.ofDays(6))
        val incomplete = insertRow("PUBLISHED", publishedAgo = Duration.ZERO)

        resubmitter.resubmitAndPurge()

        assertEquals(setOf(recent, incomplete), probes())
    }

    @Test
    fun `다른 쪽이 락을 쥐고 있으면 풀릴 때까지 기다린다`() {
        val token = checkNotNull(distributedLock.tryAcquire(EventPublicationResubmitter.LOCK_KEY))
        val executor = Executors.newSingleThreadExecutor()
        var released = false
        try {
            val run = executor.submit { resubmitter.resubmitAndPurge() }
            assertFailsWith<TimeoutException> { run.get(500, TimeUnit.MILLISECONDS) }
            distributedLock.release(EventPublicationResubmitter.LOCK_KEY, token)
            released = true
            run.get(5, TimeUnit.SECONDS)
        } finally {
            // 단언이 실패해도 락을 남기지 않는다. 이미 풀었으면 다시 풀지 않는다("만료됐다" 경고가 남는다).
            if (!released) distributedLock.release(EventPublicationResubmitter.LOCK_KEY, token)
            executor.shutdownNow()
        }
    }

    @Test
    fun `작업이 공용 스케줄러에 올라간다`() {
        val tasks = scheduledTasks.flatMap { it.scheduledTasks }.map { it.task.toString() }

        assertTrue(tasks.any { EventPublicationResubmitter::class.java.name in it }, "공용 스케줄러에 없다: $tasks")
    }

    @Test
    fun `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀌고 완료된 재전송 행은 완료로 돌린다`() {
        val stalePublished = insertRow("PUBLISHED", publishedAgo = Duration.ofMinutes(6))
        val staleProcessing = insertRow("PROCESSING", publishedAgo = Duration.ofMinutes(6))
        val freshPublished = insertRow("PUBLISHED", publishedAgo = Duration.ofMinutes(4))
        val staleResubmitted = insertRow("RESUBMITTED", publishedAgo = Duration.ofHours(1), resubmittedAgo = Duration.ofMinutes(6))
        val freshResubmitted = insertRow("RESUBMITTED", publishedAgo = Duration.ofHours(1), resubmittedAgo = Duration.ZERO)
        // 완료된 행을 다시 표시한 뒤 완료 표시 전에 프로세스가 죽으면 이렇게 남는다.
        // 오래된 행이라 끊긴 기록 조건에서 완료 여부를 빼면 FAILED가 된다.
        val completedResubmitted = insertRow("RESUBMITTED", publishedAgo = Duration.ofHours(1), completedAgo = Duration.ZERO)

        resubmitter.markStalePublicationsFailed()

        assertEquals(
            mapOf(
                stalePublished to "FAILED", staleProcessing to "FAILED", freshPublished to "PUBLISHED",
                staleResubmitted to "FAILED", freshResubmitted to "RESUBMITTED", completedResubmitted to "COMPLETED",
            ),
            statuses(),
        )
    }

    @Test
    fun `보존 기간이 0 이하면 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            EventPublicationResubmitter(failed, completed, dataSource, Clock.systemUTC(), Duration.ofMinutes(5), Duration.ZERO, SimpleMeterRegistry())
        }
    }

    /** 이 클래스만 쓰는 가짜 이벤트 타입의 행. 작업이 스스로 돌지 않으므로 넣은 상태 그대로 남는다. */
    private fun insertRow(
        status: String,
        publishedAgo: Duration,
        resubmittedAgo: Duration? = null,
        completedAgo: Duration? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbcTemplate.update(
            """
            insert into event_publication
                (id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                 completion_attempts, last_resubmission_date)
            values (?, 'com.example.Probe.on(com.example.Probe)', 'com.example.Probe', '{}', ?, ?, ?, 1, ?)
            """.trimIndent(),
            id, Timestamp.from(now.minus(publishedAgo)), completedAgo?.let { Timestamp.from(now.minus(it)) }, status,
            resubmittedAgo?.let { Timestamp.from(now.minus(it)) },
        )
        return id
    }

    private fun statuses(): Map<UUID, String> =
        jdbcTemplate.queryForList("select id, status from event_publication where event_type = 'com.example.Probe'")
            .associate { it["id"] as UUID to it["status"] as String }

    private fun probes(): Set<UUID> =
        jdbcTemplate.queryForList("select id from event_publication where event_type = 'com.example.Probe'", UUID::class.java)
            .filterNotNull().toSet()
}
