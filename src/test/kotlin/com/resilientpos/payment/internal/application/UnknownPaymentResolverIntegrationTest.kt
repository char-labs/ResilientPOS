package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.adapter.gateway.ScriptedPaymentGateway
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.platform.DistributedLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.hibernate.SessionFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.config.ScheduledTaskHolder

/**
 * 주기 작업의 본문을 직접 부른다. 테스트 컨텍스트는 주기를 하루로 늘려(`PostgresIntegrationTest`) 작업이 스스로 돌지 않는다.
 * DB를 다른 테스트와 함께 쓰므로 작업은 다른 테스트가 남긴 `UNKNOWN` 시도도 함께 집는다. 단언은 이 테스트가 만든 시도만 본다.
 * 시도는 [FakePaymentGateway]의 끝자리(2·3·4)로 `UNKNOWN`까지 태워 만든다. 조회 순서와 한 건 실패는 공용 [ScriptedPaymentGateway]로 보고 건다.
 */
class UnknownPaymentResolverIntegrationTest(
    private val resolver: UnknownPaymentResolver,
    private val orderFacade: OrderCommandFacade,
    private val jdbcTemplate: JdbcTemplate,
    private val gateway: ScriptedPaymentGateway,
    private val distributedLock: DistributedLock,
    private val scheduledTasks: ObjectProvider<ScheduledTaskHolder>,
    private val repository: PaymentAttemptRepository,
    private val meterRegistry: MeterRegistry,
    private val paymentManagement: PaymentManagement,
    private val clock: Clock,
    entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val created = mutableListOf<PaymentId>()
    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private var statisticsWereEnabled = false

    @BeforeTest
    fun enableStatistics() {
        statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun cleanUp() {
        statistics.isStatisticsEnabled = statisticsWereEnabled
        jdbcTemplate.update("delete from t_payment_attempt where id = ?", cancellationId)
        // 경과 시간 gauge 테스트가 직접 넣은 작은 id의 행은 다음 실행과 겹치지 않게 지운다.
        jdbcTemplate.update("delete from t_payment_attempt where id::text like '00000000-0000-7000-8000-%'")
        // 계속 불명으로 남는 시도는 다른 테스트의 작업이 다시 집지 않게 닫아 둔다.
        created.forEach { jdbcTemplate.update("update t_payment_attempt set status = 'DECLINED' where id = ? and status = 'UNKNOWN'", it.value) }
        // 커서가 이 테스트의 행 사이에 남지 않게 한 바퀴를 돌려 끝에 닿게 한다(닫은 행은 조회되지 않는다).
        resolver.resolveUnknown()
    }

    private val cancellationId = UUID.randomUUID()

    private fun status(paymentId: PaymentId): String =
        jdbcTemplate.queryForObject("select status from t_payment_attempt where id = ?", String::class.java, paymentId.value)!!

    /** 주문을 checkout해 시도가 [expected]가 될 때까지 기다리고 그 시도를 돌려준다. */
    private fun attemptAt(unitPrice: String, expected: PaymentAttemptStatus): PaymentId {
        val orderId = orderFacade.createAndRequestCheckout(
            OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice))),
        ).value
        fun current() = jdbcTemplate.queryForList("select id, status from t_payment_attempt where order_id = ?", orderId).singleOrNull()
        awaitUntil("시도가 $expected 가 된다", { ". 현재: ${current()}" }) { current()?.get("status") == expected.name }
        return PaymentId(current()!!["id"] as UUID).also { created += it }
    }

    @Test
    fun `작업 한 번이 UNKNOWN 승인 시도만 조회 결과대로 확정한다`() {
        val approvedAtOnce = attemptAt("4600", PaymentAttemptStatus.APPROVED)
        val declinedAtOnce = attemptAt("4601", PaymentAttemptStatus.DECLINED)
        val resolvesToApproved = attemptAt("4602", PaymentAttemptStatus.UNKNOWN)
        val resolvesToDeclined = attemptAt("4603", PaymentAttemptStatus.UNKNOWN)
        val staysUnknown = attemptAt("4604", PaymentAttemptStatus.UNKNOWN)

        resolver.resolveUnknown()

        assertEquals("APPROVED", status(resolvesToApproved))
        assertEquals("DECLINED", status(resolvesToDeclined))
        assertEquals("UNKNOWN", status(staysUnknown))
        assertEquals("APPROVED", status(approvedAtOnce))
        assertEquals("DECLINED", status(declinedAtOnce))
        assertTrue(gateway.queried().containsAll(listOf(resolvesToApproved, resolvesToDeclined, staysUnknown)))
        assertTrue(approvedAtOnce !in gateway.queried() && declinedAtOnce !in gateway.queried(), "UNKNOWN이 아닌 시도는 조회하지 않는다")
    }

    /** 주문 없이 승인 시도 행만 JDBC로 넣는다. 주문마다 새 `order_id`라 진행 중 승인 unique에 걸리지 않는다. */
    private fun insertUnknownAuthorization(id: UUID, amount: Int) {
        jdbcTemplate.update(
            """
            insert into t_payment_attempt
                (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status,
                 provider_reference, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'AUTHORIZATION', ?, 'KRW', 'UNKNOWN', null, now(), now())
            """.trimIndent(),
            id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amount,
        )
        created += PaymentId(id)
    }

    private fun unknownCount(): Long = jdbcTemplate.queryForObject(
        "select count(*) from t_payment_attempt where kind = 'AUTHORIZATION' and status = 'UNKNOWN' and deleted_at is null",
        Long::class.java,
    )!!

    @Test
    fun `영영 불명인 건이 100건을 넘어도 그 뒤의 새 건을 몇 주기 안에 확정한다`() {
        // 가장 오래된 id부터 100건은 계속 불명(끝자리 4), 그보다 새 id 하나는 조회하면 승인(끝자리 2)이다.
        val ids = List(101) { UuidV7IdentifierGenerator().next() }.sorted()
        ids.take(100).forEach { insertUnknownAuthorization(it, 4004) }
        val newest = PaymentId(ids.last())
        insertUnknownAuthorization(newest.value, 4602)
        val cycles = Math.ceilDiv(unknownCount(), 100L).toInt() + 1

        repeat(cycles) { if (status(newest) == "UNKNOWN") resolver.resolveUnknown() }

        assertEquals("APPROVED", status(newest), "$cycles 주기 안에 새 건이 조회되지 않았다")
    }

    @Test
    fun `UNKNOWN 취소 시도는 조회하지 않고 그대로 둔다`() {
        jdbcTemplate.update(
            """
            insert into t_payment_attempt
                (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status,
                 provider_reference, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'CANCELLATION', 4600, 'KRW', 'UNKNOWN', null, now(), now())
            """.trimIndent(),
            cancellationId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        )

        resolver.resolveUnknown()

        assertTrue(PaymentId(cancellationId) !in gateway.queried(), "취소 시도를 조회했다")
        assertEquals("UNKNOWN", status(PaymentId(cancellationId)))
    }

    @Test
    fun `대상 조회는 kind와 status를 바인드 파라미터가 아니라 리터럴로 렌더한다`() {
        statistics.clear()

        repository.findUnknownAuthorizationIds(null, 1)

        val queries: Array<String> = statistics.queries
        val rendered = queries.single { "PaymentAttemptEntity" in it }
        assertTrue("PaymentAttemptStatus.UNKNOWN" in rendered && "PaymentAttemptKind.AUTHORIZATION" in rendered, "리터럴이 없다: $rendered")
        assertTrue("reconciliationRequestedAt" in rendered, "넘긴 시도를 거르지 않는다: $rendered")
        assertTrue("?" !in rendered && ":" !in rendered, "바인드 파라미터가 있다: $rendered")
    }

    @Test
    fun `오래된 시도부터 조회한다`() {
        val first = attemptAt("4614", PaymentAttemptStatus.UNKNOWN)
        val second = attemptAt("4624", PaymentAttemptStatus.UNKNOWN)
        val third = attemptAt("4634", PaymentAttemptStatus.UNKNOWN)
        // 행을 고쳐 물리적 저장 순서를 바꾼다. 정렬 없이 읽으면 가장 오래된 시도가 맨 뒤로 간다.
        jdbcTemplate.update("update t_payment_attempt set provider_reference = 'touched' where id = ?", first.value)

        resolver.resolveUnknown()

        assertEquals(listOf(first, second, third), gateway.queried().filter { it in setOf(first, second, third) })
    }

    @Test
    fun `한 건이 던져도 나머지는 처리한다`() {
        val before = attemptAt("4642", PaymentAttemptStatus.UNKNOWN)
        val failing = attemptAt("4652", PaymentAttemptStatus.UNKNOWN)
        val after = attemptAt("4662", PaymentAttemptStatus.UNKNOWN)
        gateway.failQuery(failing)

        resolver.resolveUnknown()

        assertEquals("APPROVED", status(before))
        assertEquals("UNKNOWN", status(failing))
        assertEquals("APPROVED", status(after))
        assertTrue(failing in gateway.queried())
    }

    @Test
    fun `다른 쪽이 락을 쥐고 있으면 풀릴 때까지 기다린다`() {
        val waiting = attemptAt("4672", PaymentAttemptStatus.UNKNOWN)
        val token = checkNotNull(distributedLock.tryAcquire(UnknownPaymentResolver.LOCK_KEY))
        val executor = Executors.newSingleThreadExecutor()
        var released = false
        try {
            val run = executor.submit { resolver.resolveUnknown() }
            assertFailsWith<TimeoutException> { run.get(500, TimeUnit.MILLISECONDS) }
            assertTrue(gateway.queried().isEmpty(), "락을 쥔 동안 조회했다: ${gateway.queried()}")
            distributedLock.release(UnknownPaymentResolver.LOCK_KEY, token)
            released = true
            run.get(5, TimeUnit.SECONDS)
            assertTrue(waiting in gateway.queried(), "락이 풀린 뒤 조회하지 않았다")
        } finally {
            if (!released) distributedLock.release(UnknownPaymentResolver.LOCK_KEY, token)
            executor.shutdownNow()
        }
    }

    @Test
    fun `한 주기 뒤 gauge가 그 주기가 확정한 시도를 뺀 결과 불명 승인 시도 수와 같다`() {
        attemptAt("4604", PaymentAttemptStatus.UNKNOWN)
        val confirmedInCycle = attemptAt("4612", PaymentAttemptStatus.UNKNOWN)
        val before = unknownCount()

        resolver.resolveUnknown()

        // gauge는 그 주기의 조회가 끝난 뒤 센 값이다. 이 주기에 확정된 건(끝자리 2)은 빠져 있다.
        assertEquals("APPROVED", status(confirmedInCycle))
        assertTrue(unknownCount() < before)
        assertEquals(unknownCount().toDouble(), meterRegistry.get("payment.attempt.unknown").gauge().value())
    }

    /** 넘긴 시도를 만든다. 조회 횟수가 상한(3)에 닿아 넘긴 것과 같은 행 상태다. */
    private fun markHandedOver(paymentId: PaymentId) {
        jdbcTemplate.update(
            "update t_payment_attempt set query_count = 3, reconciliation_requested_at = now() where id = ?",
            paymentId.value,
        )
    }

    @Test
    fun `넘긴 시도는 작업이 고르지 않고 조회하지 않는다`() {
        val handedOver = attemptAt("4684", PaymentAttemptStatus.UNKNOWN)
        val resolvable = attemptAt("4692", PaymentAttemptStatus.UNKNOWN)
        markHandedOver(handedOver)

        resolver.resolveUnknown()

        assertTrue(handedOver !in gateway.queried(), "넘긴 시도를 조회했다")
        assertEquals("UNKNOWN", status(handedOver))
        assertEquals("APPROVED", status(resolvable))
        assertTrue(handedOver !in repository.findUnknownAuthorizationIds(null, 10_000))
    }

    private fun pendingCount(): Long = jdbcTemplate.queryForObject(
        "select count(*) from t_payment_attempt where kind = 'AUTHORIZATION' and status = 'UNKNOWN' and reconciliation_requested_at is not null and deleted_at is null",
        Long::class.java,
    )!!

    @Test
    fun `한 주기 뒤 pending gauge가 넘긴 뒤 아직 UNKNOWN인 승인 시도 수와 같다`() {
        markHandedOver(attemptAt("4694", PaymentAttemptStatus.UNKNOWN))
        markHandedOver(attemptAt("4704", PaymentAttemptStatus.UNKNOWN))
        attemptAt("4714", PaymentAttemptStatus.UNKNOWN)
        val expected = jdbcTemplate.queryForObject(
            "select count(*) from t_payment_attempt where kind = 'AUTHORIZATION' and status = 'UNKNOWN' and reconciliation_requested_at is not null and deleted_at is null",
            Long::class.java,
        )!!

        resolver.resolveUnknown()

        assertTrue(expected >= 2)
        assertEquals(expected.toDouble(), meterRegistry.get("payment.attempt.reconciliation.pending").gauge().value())
        // 기존 gauge의 뜻(넘긴 시도를 포함한 결과 불명 전체)은 그대로다.
        assertTrue(meterRegistry.get("payment.attempt.unknown").gauge().value() >= expected)
    }

    @Test
    fun `그 주기에 넘긴 시도가 같은 주기 뒤 pending gauge에 보인다`() {
        val handedOverInCycle = attemptAt("4724", PaymentAttemptStatus.UNKNOWN)
        // 조회 횟수가 상한(3) 하나 전이라 이 주기의 조회(끝자리 4는 불명)가 상한에 닿아 넘긴다.
        jdbcTemplate.update("update t_payment_attempt set query_count = 2 where id = ?", handedOverInCycle.value)
        val pendingBefore = pendingCount()

        resolver.resolveUnknown()

        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from t_payment_attempt where id = ? and reconciliation_requested_at is not null", Long::class.java, handedOverInCycle.value))
        assertTrue(pendingCount() > pendingBefore)
        assertEquals(pendingCount().toDouble(), meterRegistry.get("payment.attempt.reconciliation.pending").gauge().value())
    }

    /**
     * 가장 오래된 행은 `id` 오름차순 첫 행이라 다른 테스트가 남긴 행보다 앞서도록 아주 작은 id를 쓴다.
     * 시각은 JDBC로 아주 오래전으로 옮겨 두고 기대 경과 시간은 [clock] 기준으로 비교한다.
     */
    private fun lowId(n: Int) = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun insertAttempt(id: UUID, status: String, createdAt: Instant, requestedAt: Instant?) {
        jdbcTemplate.update(
            """
            insert into t_payment_attempt
                (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status,
                 provider_reference, reconciliation_requested_at, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'AUTHORIZATION', 4004, 'KRW', ?, null, ?, ?, now())
            """.trimIndent(),
            id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status,
            requestedAt?.let { java.sql.Timestamp.from(it) }, java.sql.Timestamp.from(createdAt),
        )
        created += PaymentId(id)
    }

    private fun ageSeconds(since: Instant) = Duration.between(since, clock.instant()).toMillis() / 1000.0

    private fun oldestGauge() = meterRegistry.get("payment.attempt.resolution.oldest").gauge().value()

    private fun pendingOldestGauge() = meterRegistry.get("payment.attempt.reconciliation.pending.oldest").gauge().value()

    @Test
    fun `넘기지 않은 가장 오래된 UNKNOWN 승인 시도의 경과 시간을 gauge로 낸다`() {
        val now = clock.instant()
        // 결말 난 시도는 더 오래됐고 id도 앞서지만 무시한다.
        insertAttempt(lowId(1), "DECLINED", now.minus(Duration.ofDays(20)), null)
        val oldestUnknownAt = now.minus(Duration.ofDays(3))
        insertAttempt(lowId(2), "UNKNOWN", oldestUnknownAt, null)

        resolver.resolveUnknown()

        assertEquals(ageSeconds(oldestUnknownAt), oldestGauge(), 5.0)
    }

    @Test
    fun `넘긴 시도는 첫 gauge에서 빠지고 pending gauge는 넘긴 시각부터 잰다`() {
        val now = clock.instant()
        val requestedAt = now.minus(Duration.ofDays(5))
        insertAttempt(lowId(3), "UNKNOWN", now.minus(Duration.ofDays(9)), requestedAt)
        // 넘긴 시각이 더 이른 다른 시도가 있으면 그것이 min이다. 가장 이른 값이 이 테스트의 것이 되게 더 이르게 둔다.
        val earliestRequestedAt = now.minus(Duration.ofDays(6))
        insertAttempt(lowId(4), "UNKNOWN", now.minus(Duration.ofDays(7)), earliestRequestedAt)

        resolver.resolveUnknown()

        assertEquals(ageSeconds(earliestRequestedAt), pendingOldestGauge(), 5.0)
        // 넘긴 시도의 생성 시각(9일, 7일 전)은 첫 gauge에 쓰이지 않는다.
        assertTrue(oldestGauge() < Duration.ofDays(6).seconds.toDouble(), "넘긴 시도가 첫 gauge에 섞였다: ${oldestGauge()}")
    }

    @Test
    fun `첫 갱신 전 경과 시간 gauge는 NaN이다`() {
        val registry = SimpleMeterRegistry()

        UnknownPaymentResolver(repository, paymentManagement, registry, clock)

        assertTrue(registry.get("payment.attempt.resolution.oldest").gauge().value().isNaN())
        assertTrue(registry.get("payment.attempt.reconciliation.pending.oldest").gauge().value().isNaN())
        assertEquals("seconds", registry.get("payment.attempt.resolution.oldest").gauge().id.baseUnit)
    }

    @Test
    fun `대상이 없으면 경과 시간 gauge는 0이다`() {
        val registry = SimpleMeterRegistry()
        val noTarget = object : PaymentAttemptRepository by repository {
            override fun oldestUnreconciledUnknownAuthorizationCreatedAt(): Instant? = null
            override fun oldestReconciliationRequestedAt(): Instant? = null
        }

        UnknownPaymentResolver(noTarget, paymentManagement, registry, clock).resolveUnknown()

        assertEquals(0.0, registry.get("payment.attempt.resolution.oldest").gauge().value())
        assertEquals(0.0, registry.get("payment.attempt.reconciliation.pending.oldest").gauge().value())
    }

    @Test
    fun `경과 시간을 세다 던지면 경과 시간 gauge는 마지막 성공값으로 남는다`() {
        val registry = SimpleMeterRegistry()
        val at = clock.instant().minus(Duration.ofDays(2))
        var fail = false
        val flaky = object : PaymentAttemptRepository by repository {
            override fun oldestUnreconciledUnknownAuthorizationCreatedAt(): Instant? = if (fail) error("조회 실패") else at
            override fun oldestReconciliationRequestedAt(): Instant? = at
        }
        val flakyResolver = UnknownPaymentResolver(flaky, paymentManagement, registry, clock)
        flakyResolver.resolveUnknown()
        val last = registry.get("payment.attempt.resolution.oldest").gauge().value()
        assertNotEquals(0.0, last)

        fail = true
        assertFailsWith<IllegalStateException> { flakyResolver.resolveUnknown() }

        assertEquals(last, registry.get("payment.attempt.resolution.oldest").gauge().value())
    }

    @Test
    fun `경과 시간 조회는 kind와 status를 리터럴로 렌더한다`() {
        statistics.clear()

        repository.oldestUnreconciledUnknownAuthorizationCreatedAt()
        repository.oldestReconciliationRequestedAt()

        val queries: Array<String> = statistics.queries
        val rendered: List<String> = queries.filter { "PaymentAttemptEntity" in it }
        assertEquals(2, rendered.size, "$rendered")
        rendered.forEach {
            assertTrue("PaymentAttemptStatus.UNKNOWN" in it && "PaymentAttemptKind.AUTHORIZATION" in it, "리터럴이 없다: $it")
            assertTrue("?" !in it && ":" !in it, "바인드 파라미터가 있다: $it")
        }
    }

    @Test
    fun `작업이 공용 스케줄러에 올라간다`() {
        val tasks = scheduledTasks.flatMap { it.scheduledTasks }.map { it.task.toString() }

        assertTrue(tasks.any { UnknownPaymentResolver::class.java.name in it }, "공용 스케줄러에 없다: $tasks")
    }
}
