package com.resilientpos.order.internal.application

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.order.orderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.transaction.support.TransactionOperations

/** checkout부터 결제 승인, 주문 `PAID`까지 리스너 셋이 잇따라 도는 흐름. */
class OrderMarkPaidIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
) : PostgresIntegrationTest() {

    private fun status(orderId: OrderId) =
        jdbcTemplate.orderStatus(orderId.value)

    /** 픽스처 합계는 결과가 미확인으로 남아 진짜 승인이 오지 않는다. 필요한 승인을 직접 낸다. */
    private fun approve(orderId: OrderId, kind: PaymentAttemptKind, amount: Money) = transactions.executeWithoutResult {
        eventPublisher.publishEvent(
            PaymentApproved(
                eventId = UUID.randomUUID(),
                occurredAt = Instant.now(),
                paymentId = UUID.randomUUID(),
                orderId = orderId.value,
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = 1,
                kind = kind,
                amount = amount,
                providerReference = null,
            ),
        )
    }

    private fun deadLetters(orderId: OrderId) = KafkaTestSupport.records(PaymentApproved.TOPIC + ".DLT", orderId.value)

    @Test
    fun `결제가 승인되면 주문이 PAID가 된다`() {
        // 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다.
        val orderId = orderFacade.createAndRequestCheckout(
            OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line("4500"))),
        )

        awaitUntil("PAID가 된다") { status(orderId) == "PAID" }
    }

    @Test
    fun `확정값과 다른 승인이면 주문은 그대로이고 승인 메시지가 DLT로 간다`() {
        val orderId = orderFacade.createAndRequestCheckout()

        approve(orderId, PaymentAttemptKind.AUTHORIZATION, Money.of("9999", OrderFixtures.krw))

        awaitUntil("승인 메시지가 DLT로 간다") { deadLetters(orderId).size == 1 }
        assertEquals("CHECKOUT_REQUESTED", status(orderId))
    }

    @Test
    fun `취소 승인은 확정값과 같아도 주문을 바꾸지 않는다`() {
        val orderId = orderFacade.createAndRequestCheckout()

        approve(orderId, PaymentAttemptKind.CANCELLATION, Money.of("3502", OrderFixtures.krw))

        KafkaTestSupport.awaitConsumed("order.payment-approved", PaymentApproved.TOPIC)
        assertEquals(emptyList(), deadLetters(orderId))
        assertEquals("CHECKOUT_REQUESTED", status(orderId))
    }
}
