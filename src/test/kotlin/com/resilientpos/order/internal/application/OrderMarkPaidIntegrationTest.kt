package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/** checkout부터 결제 승인, 주문 `PAID`까지 리스너 셋이 잇따라 도는 흐름. */
class OrderMarkPaidIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
) : PostgresIntegrationTest() {

    private fun checkout(lines: List<OrderCommand.Create.Line>? = null): OrderId {
        val command = lines?.let { OrderFixtures.createOrderCommand(lines = it) } ?: OrderFixtures.createOrderCommand()
        val created = orderFacade.create(command)
        orderFacade.requestCheckout(OrderCommand.RequestCheckout(created.orderId))
        return created.orderId
    }

    private fun status(orderId: OrderId) =
        jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, orderId.value)

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("10초 안에 충족되지 않았다: $description")
            Thread.sleep(20)
        }
    }

    @Test
    fun `결제가 승인되면 주문이 PAID가 된다`() {
        // 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다.
        val orderId = checkout(listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("4500", OrderFixtures.krw), 1)))

        awaitUntil("PAID가 된다") { status(orderId) == "PAID" }
    }

    @Test
    fun `확정값과 다른 승인이면 주문은 그대로이고 발행 기록이 실패로 남는다`() {
        // 픽스처 합계는 결과가 미확인으로 남아 진짜 승인이 오지 않는다. 금액이 다른 승인을 직접 낸다.
        val orderId = checkout()
        transactions.executeWithoutResult {
            eventPublisher.publishEvent(
                PaymentApproved(
                    eventId = UUID.randomUUID(),
                    occurredAt = Instant.now(),
                    paymentId = UUID.randomUUID(),
                    orderId = orderId.value,
                    storeId = OrderFixtures.createOrderCommand().storeId,
                    terminalId = OrderFixtures.createOrderCommand().terminalId,
                    checkoutNo = 1,
                    kind = PaymentAttemptKind.AUTHORIZATION,
                    amount = Money.of("9999", OrderFixtures.krw),
                    providerReference = null,
                ),
            )
        }

        awaitUntil("승인 리스너의 발행 기록이 실패로 남는다") {
            jdbcTemplate.queryForList(
                "select status from event_publication where listener_id like '%PaymentApprovedListener%' and serialized_event like ?",
                String::class.java, "%${orderId.value}%",
            ).singleOrNull() == "FAILED"
        }
        assertEquals("CHECKOUT_REQUESTED", status(orderId))
    }
}
