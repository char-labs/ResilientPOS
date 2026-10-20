package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.order.orderStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 결과 불명 결제가 조회로 확정된 뒤 주문까지 수렴하는 것을 Kafka 포함으로 끝까지 태운다.
 * 주기 작업은 스스로 돌지 않으므로(`PostgresIntegrationTest`) 본문 `resolveUnknown()`을 직접 부른다.
 * DB를 다른 테스트와 함께 쓰므로 단언은 이 테스트가 만든 주문·시도만 본다.
 */
class PaymentUnknownToOrderIntegrationTest(
    @Autowired private val resolver: UnknownPaymentResolver,
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    /**
     * [FakePaymentGateway]에 위임하되 [RETRY_APPROVED_AMOUNT]에서만 같은 금액의 두 번째 paymentId(재결제)를 승인한다.
     * Fake는 같은 금액을 다시 결과 불명으로 만들어 재결제 승인 경로를 밟을 수 없기 때문이다.
     */
    class RetryApprovingGateway : PaymentGateway {
        private val delegate = FakePaymentGateway()
        private val firstSeen = ConcurrentHashMap<Money, PaymentId>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
            val first = firstSeen.putIfAbsent(request.amount, request.paymentId)
            return if (request.amount.amount.toPlainString().startsWith(RETRY_APPROVED_AMOUNT) && first != null && first != request.paymentId) {
                AuthorizationOutcome.Approved("APPROVED-${request.paymentId.value}", request.amount)
            } else {
                delegate.authorize(request)
            }
        }

        override fun query(request: AuthorizationRequest): AuthorizationOutcome = delegate.query(request)
    }

    @TestConfiguration
    class RetryApprovingGatewayConfiguration {
        @Bean
        @Primary
        fun retryApprovingGateway() = RetryApprovingGateway()
    }

    private val created = mutableListOf<UUID>()

    @AfterTest
    fun cleanUp() {
        // 계속 불명으로 남는 시도는 다른 테스트의 작업이 다시 집지 않게 닫아 둔다.
        created.forEach { jdbcTemplate.update("update t_payment_attempt set status = 'DECLINED' where order_id = ? and status = 'UNKNOWN'", it) }
    }

    private fun checkoutOrder(unitPrice: String): UUID =
        orderFacade.createAndRequestCheckout(OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice)))).value.also { created += it }

    private fun attempts(orderId: UUID): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select id, status from t_payment_attempt where order_id = ? order by id", orderId)

    private fun awaitFirstAttempt(orderId: UUID, status: String): PaymentId {
        awaitUntil("첫 시도가 $status 가 된다. 현재: ${attempts(orderId)}") { attempts(orderId).singleOrNull()?.get("status") == status }
        return PaymentId(attempts(orderId).single()["id"] as UUID)
    }

    @Test
    fun `조회로 승인된 결과 불명 시도는 주문을 PAID로 만든다`() {
        val orderId = checkoutOrder("4802")
        awaitFirstAttempt(orderId, "UNKNOWN")

        resolver.resolveUnknown()

        awaitUntil("주문이 PAID가 된다. 현재: ${jdbcTemplate.orderStatus(orderId)} ${attempts(orderId)}") {
            jdbcTemplate.orderStatus(orderId) == "PAID"
        }
        assertEquals(listOf("APPROVED"), attempts(orderId).map { it["status"] })
    }

    @Test
    fun `조회로 거절된 시도를 재결제하면 새 시도가 승인되어 주문이 PAID가 된다`() {
        val orderId = checkoutOrder(RETRY_APPROVED_AMOUNT)
        val unknown = awaitFirstAttempt(orderId, "UNKNOWN")

        resolver.resolveUnknown()

        // 거절 뒤에도 주문은 이 회차의 결제가 끝나지 않은 CHECKOUT_REQUESTED에 머문다.
        assertEquals(listOf("DECLINED"), attempts(orderId).map { it["status"] })
        assertEquals("CHECKOUT_REQUESTED", jdbcTemplate.orderStatus(orderId))

        val retried = paymentManagement.retryAuthorization(PaymentCommand.RetryAuthorization(unknown, OrderFixtures.storeId))

        awaitUntil("주문이 PAID가 된다. 현재: ${jdbcTemplate.orderStatus(orderId)} ${attempts(orderId)}") {
            jdbcTemplate.orderStatus(orderId) == "PAID"
        }
        assertEquals(listOf(unknown.value, retried.paymentId.value), attempts(orderId).map { it["id"] })
        assertEquals(listOf("DECLINED", "APPROVED"), attempts(orderId).map { it["status"] })
    }

    @Test
    fun `조회로도 불명인 시도는 UNKNOWN으로 남고 주문은 CHECKOUT_REQUESTED에 머문다`() {
        val orderId = checkoutOrder("4804")
        awaitFirstAttempt(orderId, "UNKNOWN")

        resolver.resolveUnknown()

        assertEquals(listOf("UNKNOWN"), attempts(orderId).map { it["status"] })
        assertEquals("CHECKOUT_REQUESTED", jdbcTemplate.orderStatus(orderId))
    }

    private companion object {
        /** 끝자리 3이라 Fake가 조회로 거절하는 금액이다. */
        const val RETRY_APPROVED_AMOUNT = "4803"
    }
}
