package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.order.orderStatus
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

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
    @Autowired private val transactions: TransactionOperations,
) : PostgresIntegrationTest() {

    class DeclineFirstGateway : PaymentGateway {
        private val declined = ConcurrentHashMap.newKeySet<Money>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome =
            if (declined.add(request.amount)) {
                AuthorizationOutcome.Declined("DECLINED-${request.paymentId.value}")
            } else {
                AuthorizationOutcome.Approved("APPROVED-${request.paymentId.value}")
            }

        override fun query(request: AuthorizationRequest): AuthorizationOutcome = AuthorizationOutcome.Unknown
    }

    @TestConfiguration
    class DeclineFirstGatewayConfiguration {
        @Bean
        @Primary
        fun declineFirstGateway() = DeclineFirstGateway()
    }

    private fun checkoutOrder(unitPrice: String): UUID =
        orderFacade.createAndRequestCheckout(OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice)))).value

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id, checkout_no, status from t_payment_attempt where order_id = ? order by id",
        orderId,
    )

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

        awaitUntil("주문이 PAID가 된다. 현재: ${jdbcTemplate.orderStatus(orderId)} ${attempts(orderId)}") {
            jdbcTemplate.orderStatus(orderId) == "PAID"
        }
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

    @Test
    fun `트랜잭션 안에서 재결제를 요청해도 바깥 트랜잭션과 따로 커밋한다`() {
        val orderId = checkoutOrder("4705")
        val declined = awaitDeclined(orderId)

        // 바깥 트랜잭션을 멈추고 새 시도를 따로 커밋하므로, 바깥을 롤백해도 새 시도는 남는다.
        transactions.executeWithoutResult { outer ->
            paymentManagement.retryAuthorization(PaymentCommand.RetryAuthorization(declined))
            outer.setRollbackOnly()
        }

        assertEquals(2, attempts(orderId).size, "새 시도가 바깥 롤백에 휩쓸렸다: ${attempts(orderId)}")
    }
}
