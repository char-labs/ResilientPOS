package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.RequestCheckoutCommand
import com.resilientpos.payment.AuthorizePaymentCommand
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
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
) : PostgresIntegrationTest() {

    /** [FakePaymentGateway]에 위임하면서 시도별 호출 횟수와 호출 순간의 트랜잭션 여부를 적는다. */
    class ProbingGateway : PaymentGateway {
        private val delegate = FakePaymentGateway()
        private val calls = ConcurrentHashMap<PaymentId, AtomicInteger>()
        private val transactionActive = ConcurrentHashMap<PaymentId, Boolean>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
            calls.computeIfAbsent(request.paymentId) { AtomicInteger() }.incrementAndGet()
            transactionActive[request.paymentId] = TransactionSynchronizationManager.isActualTransactionActive()
            return delegate.authorize(request)
        }

        fun callsFor(paymentId: PaymentId) = calls[paymentId]?.get() ?: 0

        fun transactionActiveDuringCall(paymentId: PaymentId) = transactionActive[paymentId]
    }

    @TestConfiguration
    class ProbingGatewayConfiguration {
        @Bean
        @Primary
        fun probingGateway() = ProbingGateway()
    }

    private fun checkoutOrder(unitPrice: String): UUID {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(CreateOrderLine("SKU-1", "아메리카노", Money.of(unitPrice, OrderFixtures.krw), 1)),
            ),
        )
        orderFacade.requestCheckout(RequestCheckoutCommand(created.orderId))
        return created.orderId.value
    }

    private fun attempt(orderId: UUID): Map<String, Any?>? = jdbcTemplate.queryForList(
        "select id, status, provider_reference from t_payment_attempt where order_id = ?",
        orderId,
    ).singleOrNull()

    private fun paymentId(row: Map<String, Any?>) = PaymentId(row["id"] as UUID)

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("10초 안에 충족되지 않았다: $description")
            Thread.sleep(20)
        }
    }

    private fun awaitStatus(orderId: UUID, expected: PaymentAttemptStatus): Map<String, Any?> {
        awaitUntil("시도가 $expected 가 된다. 현재: ${attempt(orderId)}") { attempt(orderId)?.get("status") == expected.name }
        return requireNotNull(attempt(orderId))
    }

    /**
     * 승인 리스너가 이 주문의 시도를 **끝까지 처리했는지**를 발행 기록으로 기다린다. 성공이든 실패든 끝난 것이다.
     * 상태만 보면 0원처럼 처음부터 결말이 난 시도에서 "리스너가 막았다"와 "아직 안 돌았다"가 구분되지 않는다.
     */
    private fun awaitAuthorizationListenerFinished(orderId: UUID): String {
        val query = """
            select status from event_publication
            where listener_id like '%PaymentAttemptCreatedListener%' and serialized_event like ?
        """.trimIndent()
        var status: String? = null
        awaitUntil("승인 리스너가 끝난다") {
            status = jdbcTemplate.queryForList(query, String::class.java, "%$orderId%").singleOrNull()
            status == "COMPLETED" || status == "FAILED"
        }
        return requireNotNull(status)
    }

    @Test
    fun `승인이 나는 금액이면 시도가 APPROVED가 되고 provider 참조가 남는다`() {
        val orderId = checkoutOrder("4500")

        val row = awaitStatus(orderId, PaymentAttemptStatus.APPROVED)

        val reference = assertNotNull(row["provider_reference"]) as String
        assertTrue(reference.startsWith(FakePaymentGateway.REFERENCE_PREFIX))
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
        val listener = awaitAuthorizationListenerFinished(orderId)

        assertEquals("COMPLETED", listener)
        val row = requireNotNull(attempt(orderId))
        assertEquals(PaymentAttemptStatus.APPROVED.name, row["status"])
        assertEquals(0, gateway.callsFor(paymentId(row)))
    }

    @Test
    fun `provider를 부르는 동안 트랜잭션이 없다`() {
        val orderId = checkoutOrder("4500")

        val row = awaitStatus(orderId, PaymentAttemptStatus.APPROVED)

        // 트랜잭션 안에서 부르면 provider가 답할 때까지 DB 커넥션을 쥔다(도메인 모델 §4.6·§6).
        assertEquals(false, gateway.transactionActiveDuringCall(paymentId(row)))
    }

    @Test
    fun `결말이 난 시도에 다시 승인을 요청해도 provider를 다시 부르지 않는다`() {
        val orderId = checkoutOrder("4500")
        val paymentId = paymentId(awaitStatus(orderId, PaymentAttemptStatus.APPROVED))

        // 같은 사실이 다시 배달된 것과 같은 상황을 직접 만든다. 기다리기만 해서는 재배달이 일어나지 않는다.
        val result = paymentManagement.authorize(AuthorizePaymentCommand(paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
        assertEquals(1, gateway.callsFor(paymentId))
    }
}
