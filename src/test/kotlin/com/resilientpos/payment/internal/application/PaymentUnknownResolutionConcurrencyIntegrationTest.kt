package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 같은 `UNKNOWN` 시도에 `resolveUnknown`이 겹칠 때 확정이 한 번만 적용되는지 본다.
 *
 * provider가 두 조회에 **서로 다른** 답을 주게 만든다. 잠근 뒤 `UNKNOWN` 재확인이 없으면 둘 다 적용해
 * 승인이 거절로 조용히 바뀌고 이벤트가 둘 나간다. 시도는 SQL로 직접 넣는다. 이벤트로 만들면 승인 리스너가 끼어든다.
 */
class PaymentUnknownResolutionConcurrencyIntegrationTest(
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val gateway: RendezvousGateway,
    @Autowired private val declinedEvents: DeclinedRecorder,
) : PostgresIntegrationTest() {

    /** 거절 사실은 소비자가 없어 발행 기록에 남지 않는다. 같은 프로세스에서 발행된 것을 직접 받아 적는다. */
    class DeclinedRecorder {
        private val received = CopyOnWriteArrayList<PaymentEvent.Declined>()

        @EventListener
        fun record(event: PaymentEvent.Declined) {
            received += event
        }

        fun countFor(paymentId: PaymentId) = received.count { it.paymentId == paymentId }
    }

    /** 두 조회가 모두 도착할 때까지 붙잡아 둔다. 먼저 온 쪽은 승인, 나중 쪽은 거절을 받는다. */
    class RendezvousGateway : PaymentGateway {
        private val arrived = CountDownLatch(2)
        val queryCalls = AtomicInteger()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome = AuthorizationOutcome.Unknown

        override fun query(request: AuthorizationRequest): AuthorizationOutcome {
            val position = queryCalls.getAndIncrement()
            arrived.countDown()
            check(arrived.await(5, TimeUnit.SECONDS)) { "두 조회가 provider에서 만나지 못했다" }
            return if (position == 0) AuthorizationOutcome.Approved("FIRST") else AuthorizationOutcome.Declined("SECOND")
        }
    }

    @TestConfiguration
    class RendezvousGatewayConfiguration {
        @Bean
        @Primary
        fun rendezvousGateway() = RendezvousGateway()

        @Bean
        fun declinedRecorder() = DeclinedRecorder()
    }

    private fun insertUnknownAttempt(): PaymentId {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into t_payment_attempt
                (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status,
                 provider_reference, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'AUTHORIZATION', 4502, 'KRW', 'UNKNOWN', null, now(), now())
            """.trimIndent(),
            id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        )
        return PaymentId(id)
    }

    @Test
    fun `같은 시도의 확정이 겹쳐도 한 번만 적용되고 이벤트는 하나다`() {
        val paymentId = insertUnknownAttempt()
        val executor = Executors.newFixedThreadPool(2)

        val results: List<PaymentCommand.ResolveUnknown.Result> = try {
            List(2) {
                executor.submit<PaymentCommand.ResolveUnknown.Result> {
                    paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val stored = jdbcTemplate.queryForObject<String>("select status from t_payment_attempt where id = ?", paymentId.value)
        assertEquals(1, results.map { it.status }.distinct().size, "두 호출이 서로 다른 결말을 돌려받았다: $results")
        assertEquals(stored, results.first().status.name)
        // 조회는 읽기라 겹쳐도 해가 없다. 두 번 불렸다는 사실만 고정한다.
        assertEquals(2, gateway.queryCalls.get())
        val publications = requireNotNull(jdbcTemplate.queryForObject<Int>(
            "select count(*) from event_publication where event_type like '%PaymentApproved' and serialized_event like ?",
            "%${paymentId.value}%",
        ))
        // 승인 발행 기록과 거절 사실을 합쳐 하나다. 둘 다 적용되면 2가 된다.
        assertEquals(1, publications + declinedEvents.countFor(paymentId))
    }
}
