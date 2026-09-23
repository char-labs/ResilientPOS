package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.RequestCheckoutCommand
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

/**
 * checkout 요청부터 provider 결과가 시도에 적용되기까지를 끝까지 태운다.
 * 리스너 둘이 잇따라 비동기로 도므로 조건이 만족될 때까지 짧게 반복해서 기다린다.
 *
 * 경로는 금액으로 고른다. [FakePaymentGateway]가 최소 단위 끝자리를 보고 결말을 정한다.
 */
class PaymentAuthorizationIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val callCount: AtomicInteger,
) : PostgresIntegrationTest() {

    /**
     * provider를 부른 횟수를 센다. 0원 주문이 provider를 부르지 않는다는 것은 상태로는 확인할 수 없다 —
     * 0원 시도는 부르든 안 부르든 `APPROVED`이기 때문이다.
     */
    @TestConfiguration
    class CountingGatewayConfiguration {

        @Bean
        fun callCount() = AtomicInteger()

        @Bean
        @Primary
        fun countingGateway(callCount: AtomicInteger): PaymentGateway {
            val delegate = FakePaymentGateway()
            return object : PaymentGateway {
                override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
                    callCount.incrementAndGet()
                    return delegate.authorize(request)
                }
            }
        }
    }

    @AfterTest
    fun resetCount() {
        callCount.set(0)
    }

    private fun attempt(orderId: UUID): Map<String, Any?>? = jdbcTemplate.queryForList(
        "select status, provider_reference from t_payment_attempt where order_id = ?",
        orderId,
    ).singleOrNull()

    private fun awaitStatus(orderId: UUID, expected: PaymentAttemptStatus): Map<String, Any?> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val row = attempt(orderId)
            if (row?.get("status") == expected.name) return row
            if (System.nanoTime() >= deadline) {
                fail("10초 안에 $expected 가 되지 않았다. 현재: ${attempt(orderId)}")
            }
            Thread.sleep(20)
        }
    }

    private fun checkoutOrder(unitPrice: String, quantity: Int = 1): UUID {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(
                    CreateOrderLine("SKU-1", "아메리카노", Money.of(unitPrice, OrderFixtures.krw), quantity),
                ),
            ),
        )
        orderFacade.requestCheckout(RequestCheckoutCommand(created.orderId))
        return created.orderId.value
    }

    @Test
    fun `승인이 나는 금액이면 시도가 APPROVED가 되고 provider 참조가 남는다`() {
        val orderId = checkoutOrder("4500")

        val row = awaitStatus(orderId, PaymentAttemptStatus.APPROVED)

        val reference = assertNotNull(row["provider_reference"]) as String
        assertEquals(true, reference.startsWith(FakePaymentGateway.REFERENCE_PREFIX))
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

        val row = awaitStatus(orderId, PaymentAttemptStatus.APPROVED)

        assertNull(row["provider_reference"])
        assertEquals(0, callCount.get())
    }

    @Test
    fun `provider는 시도마다 한 번만 불린다`() {
        val orderId = checkoutOrder("4500")

        awaitStatus(orderId, PaymentAttemptStatus.APPROVED)
        // 결말이 난 뒤 같은 사실이 다시 와도 다시 부르지 않는다는 것을 상태로는 볼 수 없어 횟수로 본다.
        Thread.sleep(200)
        assertEquals(1, callCount.get())
    }
}
