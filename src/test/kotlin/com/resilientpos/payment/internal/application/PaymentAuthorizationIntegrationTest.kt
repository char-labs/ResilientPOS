package com.resilientpos.payment.internal.application

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import jakarta.persistence.EntityManagerFactory
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import javax.sql.DataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * checkout 요청부터 provider 결과가 시도에 적용되기까지를 끝까지 태운다.
 * 경로는 금액으로 고른다. [FakePaymentGateway]가 최소 단위 끝자리를 보고 결말을 정한다.
 *
 * 호출 횟수를 **시도별로** 센다. 컨텍스트 하나에 카운터 하나를 두면 앞 테스트의 늦게 도착한 호출이
 * 다음 테스트에 섞인다 — 리스너가 다른 스레드에서 돌기 때문이다.
 */
class PaymentAuthorizationIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val gateway: ProbingGateway,
    @Autowired private val transactions: TransactionOperations,
) : PostgresIntegrationTest() {

    /**
     * [FakePaymentGateway]에 위임하면서 시도별 호출 횟수와, 호출 순간 이 스레드에 DB 자원(EntityManager·커넥션)이
     * 묶여 있었는지를 적는다. 트랜잭션 여부만 보면 빈 트랜잭션 스코프에 묶인 EntityManager를 놓친다.
     * 끝자리 3이면 어댑터가 포트 계약을 어기고 던진 경우를 흉내 낸다.
     */
    class ProbingGateway(private val emf: EntityManagerFactory, private val dataSource: DataSource) : PaymentGateway {
        private val delegate = FakePaymentGateway()
        private val calls = ConcurrentHashMap<PaymentId, AtomicInteger>()
        private val resourceBound = ConcurrentHashMap<PaymentId, Boolean>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
            calls.computeIfAbsent(request.paymentId) { AtomicInteger() }.incrementAndGet()
            resourceBound[request.paymentId] =
                TransactionSynchronizationManager.hasResource(emf) || TransactionSynchronizationManager.hasResource(dataSource)
            if (request.amount.amount.unscaledValue().mod(BigInteger.TEN).toInt() == 3) error("어댑터가 던졌다")
            return delegate.authorize(request)
        }

        fun callsFor(paymentId: PaymentId) = calls[paymentId]?.get() ?: 0

        fun resourceBoundDuringCall(paymentId: PaymentId) = resourceBound[paymentId]
    }

    @TestConfiguration
    class ProbingGatewayConfiguration {
        @Bean
        @Primary
        fun probingGateway(emf: EntityManagerFactory, dataSource: DataSource) = ProbingGateway(emf, dataSource)
    }

    private fun checkoutOrder(unitPrice: String): UUID =
        orderFacade.createAndRequestCheckout(OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice)))).value

    private fun attempt(orderId: UUID): Map<String, Any?>? = jdbcTemplate.queryForList(
        "select id, status, provider_reference from t_payment_attempt where order_id = ?",
        orderId,
    ).singleOrNull()

    private fun paymentId(row: Map<String, Any?>) = PaymentId(row["id"] as UUID)

    private fun awaitStatus(orderId: UUID, expected: PaymentAttemptStatus): Map<String, Any?> {
        awaitUntil("시도가 $expected 가 된다", { ". 현재: ${attempt(orderId)}" }) { attempt(orderId)?.get("status") == expected.name }
        return requireNotNull(attempt(orderId))
    }

    /**
     * 승인 consumer가 이 주문의 시도를 **끝까지 처리했는지** 기다리고 DLT로 갔는지 돌려준다.
     * 상태만 보면 0원처럼 처음부터 결말이 난 시도에서 "리스너가 막았다"와 "아직 안 돌았다"가 구분되지 않는다.
     * 발행 기록이 먼저 완료돼야 메시지가 토픽에 있으므로 그 뒤에 consumer group의 offset을 본다.
     */
    private fun awaitAuthorizationConsumed(orderId: UUID): Boolean {
        awaitUntil("승인 요청이 브로커로 간다") { KafkaTestSupport.records(PaymentEvent.AttemptCreated.TOPIC, orderId).isNotEmpty() }
        KafkaTestSupport.awaitConsumed("payment.attempt-created", PaymentEvent.AttemptCreated.TOPIC)
        return KafkaTestSupport.records(PaymentEvent.AttemptCreated.TOPIC + ".DLT", orderId).isNotEmpty()
    }

    @Test
    fun `승인이 나는 금액이면 시도가 APPROVED가 되고 provider 참조가 남는다`() {
        val orderId = checkoutOrder("4500")

        val row = awaitStatus(orderId, PaymentAttemptStatus.APPROVED)

        val reference = assertNotNull(row["provider_reference"]) as String
        assertTrue(reference.startsWith("FAKE-"))
    }

    @Test
    fun `거절이 나는 금액이면 시도가 DECLINED가 된다`() {
        val orderId = checkoutOrder("4501")

        val row = awaitStatus(orderId, PaymentAttemptStatus.DECLINED)

        assertNotNull(row["provider_reference"])
    }

    @Test
    fun `미확인이 나는 금액이면 시도가 UNKNOWN이 되고 참조가 없다`() {
        val orderId = checkoutOrder("4502")

        val row = awaitStatus(orderId, PaymentAttemptStatus.UNKNOWN)

        assertNull(row["provider_reference"])
    }

    @Test
    fun `0원 주문은 provider를 부르지 않는다`() {
        val orderId = checkoutOrder("0")

        // 리스너가 실제로 돌아 끝난 뒤에 센다. 시도는 만들어질 때부터 APPROVED라 상태로는 기다릴 수 없다.
        val deadLettered = awaitAuthorizationConsumed(orderId)

        assertEquals(false, deadLettered)
        val row = requireNotNull(attempt(orderId))
        assertEquals(PaymentAttemptStatus.APPROVED.name, row["status"])
        assertEquals(0, gateway.callsFor(paymentId(row)))
    }

    @Test
    fun `provider를 부르는 동안 DB 자원을 쥐지 않는다`() {
        val orderId = checkoutOrder("4500")

        val row = awaitStatus(orderId, PaymentAttemptStatus.APPROVED)

        // 쥐고 있으면 provider가 답할 때까지 커넥션이 묶인다(도메인 모델 §4.6·§6).
        assertEquals(false, gateway.resourceBoundDuringCall(paymentId(row)))
    }

    @Test
    fun `트랜잭션 안에서 승인을 요청해도 바깥 트랜잭션과 따로 적용한다`() {
        // 이벤트로 만들면 승인 리스너가 먼저 처리해 버린다. REQUESTED로 남은 시도를 직접 넣는다.
        val paymentId = PaymentId(UUID.randomUUID())
        jdbcTemplate.update(
            """
            insert into t_payment_attempt (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency,
                status, provider_reference, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'AUTHORIZATION', 4500, 'KRW', 'REQUESTED', null, now(), now())
            """.trimIndent(),
            paymentId.value, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        )

        // 바깥 트랜잭션을 멈추고 provider를 부른 뒤 적용을 따로 커밋하므로, 바깥을 롤백해도 적용은 남는다.
        transactions.executeWithoutResult { outer ->
            paymentManagement.authorize(PaymentCommand.Authorize(paymentId))
            outer.setRollbackOnly()
        }

        assertEquals(1, gateway.callsFor(paymentId))
        assertEquals(false, gateway.resourceBoundDuringCall(paymentId))
        assertEquals(
            PaymentAttemptStatus.APPROVED.name,
            jdbcTemplate.queryForObject<String>("select status from t_payment_attempt where id = ?", paymentId.value),
        )
    }

    @Test
    fun `어댑터가 모르는 예외를 던지면 재시도 없이 시도는 REQUESTED인 채 DLT로 간다`() {
        val orderId = checkoutOrder("4503")

        val deadLettered = awaitAuthorizationConsumed(orderId)

        assertEquals(true, deadLettered)
        val row = requireNotNull(attempt(orderId))
        assertEquals(PaymentAttemptStatus.REQUESTED.name, row["status"])
        // 재시도 허용 목록 밖이라 provider를 다시 부르지 않는다. 다시 부르면 멱등 키 없는 provider에서 이중 승인 위험이다.
        assertEquals(1, gateway.callsFor(paymentId(row)))
    }

    @Test
    fun `결말이 난 시도에 다시 승인을 요청해도 provider를 다시 부르지 않는다`() {
        val orderId = checkoutOrder("4500")
        val paymentId = paymentId(awaitStatus(orderId, PaymentAttemptStatus.APPROVED))

        // 같은 사실이 다시 배달된 것과 같은 상황을 직접 만든다. 기다리기만 해서는 재배달이 일어나지 않는다.
        val result = paymentManagement.authorize(PaymentCommand.Authorize(paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
        assertEquals(1, gateway.callsFor(paymentId))
    }
}
