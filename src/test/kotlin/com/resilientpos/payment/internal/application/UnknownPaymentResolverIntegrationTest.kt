package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import com.resilientpos.platform.DistributedLock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.config.ScheduledTaskHolder

/**
 * 주기 작업의 본문을 직접 부른다. 테스트 컨텍스트는 주기를 하루로 늘려(`PostgresIntegrationTest`) 작업이 스스로 돌지 않는다.
 * DB를 다른 테스트와 함께 쓰므로 작업은 다른 테스트가 남긴 `UNKNOWN` 시도도 함께 집는다. 단언은 이 테스트가 만든 시도만 본다.
 * 시도는 [FakePaymentGateway]의 끝자리(2·3·4)로 `UNKNOWN`까지 태워 만든다.
 */
class UnknownPaymentResolverIntegrationTest(
    @Autowired private val resolver: UnknownPaymentResolver,
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val gateway: RecordingGateway,
    @Autowired private val distributedLock: DistributedLock,
    @Autowired private val scheduledTasks: ObjectProvider<ScheduledTaskHolder>,
) : PostgresIntegrationTest() {

    /**
     * [FakePaymentGateway]에 위임하면서 `query`가 불린 순서를 적고, 지정한 시도에서는 던진다.
     * 계약상 gateway는 던지지 않는다. 한 건 실패가 배치를 막지 않는지를 보려고, 확정 트랜잭션의 DB 오류나 잠금 대기 초과 같은
     * 한 건짜리 실패를 이 방법으로 흉내 낸다.
     */
    class RecordingGateway : PaymentGateway {
        private val delegate = FakePaymentGateway()
        val queried = CopyOnWriteArrayList<PaymentId>()
        val failFor = CopyOnWriteArraySet<PaymentId>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome = delegate.authorize(request)

        override fun query(request: AuthorizationRequest): AuthorizationOutcome {
            queried += request.paymentId
            if (request.paymentId in failFor) error("조회 실패를 흉내 낸다")
            return delegate.query(request)
        }
    }

    @TestConfiguration
    class RecordingGatewayConfiguration {
        @Bean
        @Primary
        fun recordingGateway() = RecordingGateway()
    }

    private val created = mutableListOf<PaymentId>()

    @AfterTest
    fun cleanUp() {
        gateway.failFor.clear()
        gateway.queried.clear()
        // 계속 불명으로 남는 시도는 다른 테스트의 작업이 다시 집지 않게 닫아 둔다.
        created.forEach { jdbcTemplate.update("update t_payment_attempt set status = 'DECLINED' where id = ? and status = 'UNKNOWN'", it.value) }
    }

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
        assertTrue(gateway.queried.containsAll(listOf(resolvesToApproved, resolvesToDeclined, staysUnknown)))
        assertTrue(approvedAtOnce !in gateway.queried && declinedAtOnce !in gateway.queried, "UNKNOWN이 아닌 시도는 조회하지 않는다")
    }

    @Test
    fun `오래된 시도부터 조회한다`() {
        val first = attemptAt("4614", PaymentAttemptStatus.UNKNOWN)
        val second = attemptAt("4624", PaymentAttemptStatus.UNKNOWN)
        val third = attemptAt("4634", PaymentAttemptStatus.UNKNOWN)
        // 행을 고쳐 물리적 저장 순서를 바꾼다. 정렬 없이 읽으면 가장 오래된 시도가 맨 뒤로 간다.
        jdbcTemplate.update("update t_payment_attempt set provider_reference = 'touched' where id = ?", first.value)

        resolver.resolveUnknown()

        assertEquals(listOf(first, second, third), gateway.queried.filter { it in setOf(first, second, third) })
    }

    @Test
    fun `한 건이 던져도 나머지는 처리한다`() {
        val before = attemptAt("4642", PaymentAttemptStatus.UNKNOWN)
        val failing = attemptAt("4652", PaymentAttemptStatus.UNKNOWN)
        val after = attemptAt("4662", PaymentAttemptStatus.UNKNOWN)
        gateway.failFor += failing

        resolver.resolveUnknown()

        assertEquals("APPROVED", status(before))
        assertEquals("UNKNOWN", status(failing))
        assertEquals("APPROVED", status(after))
        assertTrue(failing in gateway.queried)
    }

    @Test
    fun `다른 쪽이 락을 쥐고 있으면 풀릴 때까지 기다린다`() {
        val token = checkNotNull(distributedLock.tryAcquire(UnknownPaymentResolver.LOCK_KEY))
        val executor = Executors.newSingleThreadExecutor()
        var released = false
        try {
            val run = executor.submit { resolver.resolveUnknown() }
            assertFailsWith<TimeoutException> { run.get(500, TimeUnit.MILLISECONDS) }
            distributedLock.release(UnknownPaymentResolver.LOCK_KEY, token)
            released = true
            run.get(5, TimeUnit.SECONDS)
        } finally {
            if (!released) distributedLock.release(UnknownPaymentResolver.LOCK_KEY, token)
            executor.shutdownNow()
        }
    }

    @Test
    fun `작업이 공용 스케줄러에 올라간다`() {
        val tasks = scheduledTasks.flatMap { it.scheduledTasks }.map { it.task.toString() }

        assertTrue(tasks.any { UnknownPaymentResolver::class.java.name in it }, "공용 스케줄러에 없다: $tasks")
    }
}
