package com.resilientpos.fulfillment.internal.application

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.contract.OrderPaid
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.transaction.support.TransactionOperations

/** `OrderPaid`를 받으면 그 주문의 `Fulfillment`가 하나 생긴다. */
class OrderPaidListenerIntegrationTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    @Autowired private val eventSerializer: EventSerializer,
) : PostgresIntegrationTest() {

    private val krw: Currency = Currency.getInstance("KRW")

    private fun fulfillments(orderId: UUID) =
        jdbcTemplate.queryForList("select id from t_fulfillment where order_id = ?", orderId)

    private fun orderPaid(orderId: UUID) = OrderPaid(
        eventId = UUID.randomUUID(),
        occurredAt = Instant.parse("2026-09-24T06:00:00.123456789Z"),
        orderId = orderId,
        storeId = StoreId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
        terminalId = TerminalId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
        checkoutNo = 1,
        amount = Money.of("4500", krw),
    )

    private fun publish(event: OrderPaid) = transactions.executeWithoutResult { eventPublisher.publishEvent(event) }

    /** 그 주문의 발행 기록. 리스너가 Kafka consumer라 기록은 브로커로 보내는 externalizer의 것이다. listener_id는 저장 형식이라 전체 이름으로 거른다. */
    private fun publications(orderId: UUID) = jdbcTemplate.queryForList(
        "select event_type, listener_id, serialized_event, status from event_publication where listener_id = ? and serialized_event like ?",
        LISTENER_ID, "%$orderId%",
    )

    @Test
    fun `OrderPaid를 받으면 그 주문의 Fulfillment가 하나 생긴다`() {
        val orderId = UUID.randomUUID()

        publish(orderPaid(orderId))

        awaitUntil("fulfillment가 생긴다") { fulfillments(orderId).size == 1 }
    }

    /** 브로커로 보내지 못한 기록을 resubmit하려면 저장된 이벤트가 복원돼야 한다. */
    @Test
    fun `저장된 OrderPaid를 복원하면 발행한 값과 같다`() {
        val event = orderPaid(UUID.randomUUID())
        publish(event)

        awaitUntil("브로커로 보낸다") { publications(event.orderId).singleOrNull()?.get("status") == "COMPLETED" }
        val publication = publications(event.orderId).single()

        assertEquals("com.resilientpos.contract.OrderPaid", publication["event_type"])
        val serialized = assertNotNull(publication["serialized_event"]).toString()
        assertEquals(event, eventSerializer.deserialize(serialized, OrderPaid::class.java))
    }

    /** 재배달은 실제 DB에서 기존 fulfillment를 찾아 돌려준다. unique 위반으로 실패한 것과 구분하려고 끝까지 소비됐고 DLT가 비었는지 본다. */
    @Test
    fun `같은 주문의 OrderPaid가 다시 와도 fulfillment는 하나이고 DLT로 가지 않는다`() {
        val orderId = UUID.randomUUID()

        publish(orderPaid(orderId))
        awaitUntil("첫 fulfillment가 생긴다") { fulfillments(orderId).size == 1 }
        publish(orderPaid(orderId))

        awaitUntil("두 기록이 모두 브로커로 간다") { publications(orderId).count { it["status"] == "COMPLETED" } == 2 }
        KafkaTestSupport.awaitConsumed("fulfillment.order-paid", OrderPaid.TOPIC)
        assertEquals(1, fulfillments(orderId).size)
        assertEquals(emptyList(), KafkaTestSupport.records(OrderPaid.TOPIC + ".DLT", orderId))
    }

    companion object {
        private const val LISTENER_ID =
            "org.springframework.modulith.events.support.EventExternalizerModuleListener.externalize(java.lang.Object)"
    }
}
