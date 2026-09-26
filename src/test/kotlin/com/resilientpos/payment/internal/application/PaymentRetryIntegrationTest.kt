package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 거절된 결제를 같은 checkout 회차에서 다시 시도해 주문이 결제되기까지를 끝까지 태운다.
 *
 * [FakePaymentGateway]는 금액으로만 결말을 정해서 같은 회차(같은 금액)의 재결제가 늘 다시 거절된다.
 * 그래서 이 테스트만 금액마다 **처음 한 번은 거절하고 그다음은 승인하는** gateway를 쓴다. 테스트마다 금액이 달라야 한다.
 */
class PaymentRetryIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    class DeclineFirstGateway : PaymentGateway {
        private val declined = ConcurrentHashMap.newKeySet<Money>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome =
            if (declined.add(request.amount)) {
                AuthorizationOutcome.Declined("DECLINED-${request.paymentId.value}")
            } else {
                AuthorizationOutcome.Approved("APPROVED-${request.paymentId.value}")
            }
    }

    @TestConfiguration
    class DeclineFirstGatewayConfiguration {
        @Bean
        @Primary
        fun declineFirstGateway() = DeclineFirstGateway()
    }

    private fun checkoutOrder(unitPrice: String): UUID {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of(unitPrice, OrderFixtures.krw), 1)),
            ),
        )
        orderFacade.requestCheckout(OrderCommand.RequestCheckout(created.orderId))
        return created.orderId.value
    }

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id, checkout_no, status from t_payment_attempt where order_id = ? order by id",
        orderId,
    )

    private fun orderStatus(orderId: UUID): String =
        jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, orderId)!!

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("10초 안에 충족되지 않았다: $description")
            Thread.sleep(20)
        }
    }

    private fun awaitDeclined(orderId: UUID): PaymentId {
        awaitUntil("첫 시도가 거절된다. 현재: ${attempts(orderId)}") {
            attempts(orderId).singleOrNull()?.get("status") == PaymentAttemptStatus.DECLINED.name
        }
        return PaymentId(attempts(orderId).single()["id"] as UUID)
    }

    @Test
    fun `거절 뒤 같은 회차에서 다시 시도해 승인되면 주문이 PAID가 된다`() {
        val orderId = checkoutOrder("4701")
        val declined = awaitDeclined(orderId)

        val retried = paymentManagement.retryAuthorization(PaymentCommand.RetryAuthorization(declined))

        awaitUntil("주문이 PAID가 된다. 현재: ${orderStatus(orderId)} ${attempts(orderId)}") { orderStatus(orderId) == "PAID" }
        val rows = attempts(orderId)
        assertEquals(listOf(declined.value, retried.paymentId.value), rows.map { it["id"] })
        assertEquals(listOf(1, 1), rows.map { it["checkout_no"] })
        assertEquals(listOf("DECLINED", "APPROVED"), rows.map { it["status"] })
    }

    @Test
    fun `같은 거절 시도에 재결제가 겹쳐도 새 시도는 하나다`() {
        val orderId = checkoutOrder("4703")
        val declined = awaitDeclined(orderId)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            List(2) {
                executor.submit<PaymentCommand.RetryAuthorization.Result> {
                    ready.countDown()
                    ready.await(5, TimeUnit.SECONDS)
                    paymentManagement.retryAuthorization(PaymentCommand.RetryAuthorization(declined))
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.map { it.paymentId }.distinct().size, "두 호출이 서로 다른 시도를 돌려받았다: $results")
        assertEquals(2, attempts(orderId).size)
    }
}
