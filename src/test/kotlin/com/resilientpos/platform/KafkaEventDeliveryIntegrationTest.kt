package com.resilientpos.platform

import com.resilientpos.KafkaTestSupport
import com.resilientpos.awaitUntil
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.OrderPaid
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.orderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.queryForObject
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.json.JsonMapper

/**
 * 모듈 사이 이벤트 넷이 Kafka 토픽을 거쳐 전달되는지 검사한다.
 * 토픽 이름·consumer group·key는 저장 형식이라 클래스 상수를 다시 읽지 않고 리터럴로 고정한다.
 */
class KafkaEventDeliveryIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val listenerRegistry: KafkaListenerEndpointRegistry,
    @Value("\${resilientpos.kafka.topics}") private val configuredTopics: List<String>,
) : PostgresIntegrationTest() {

    private val groups = mapOf(
        "order.payment-requested" to "payment.order-payment-requested",
        "payment.attempt-created" to "payment.attempt-created",
        "payment.approved" to "order.payment-approved",
        "order.paid" to "fulfillment.order-paid",
    )
    private val topics = groups.keys

    private fun count(sql: String, orderId: UUID) = jdbcTemplate.queryForObject<Long>(sql, orderId)

    /** 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다. */
    private fun checkoutApproved(): UUID {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("4500", OrderFixtures.krw), 1)),
            ),
        )
        orderFacade.requestCheckout(OrderCommand.RequestCheckout(created.orderId))
        return created.orderId.value
    }

    private fun publications(orderId: UUID) =
        jdbcTemplate.queryForList(
            "select listener_id, completion_date from event_publication where serialized_event like ?",
            "%$orderId%",
        )

    @Test
    fun `checkout이 네 토픽을 거쳐 PAID와 fulfillment가 되고 key는 orderId다`() {
        val orderId = checkoutApproved()

        awaitUntil("fulfillment가 생긴다") {
            jdbcTemplate.queryForObject<Long>("select count(*) from t_fulfillment where order_id = ?", orderId) == 1L
        }
        assertEquals("PAID", jdbcTemplate.orderStatus(orderId))
        topics.forEach { topic -> assertEquals(1, KafkaTestSupport.records(topic, orderId).size, "$topic 에 key가 orderId인 메시지 하나") }
    }

    @Test
    fun `발행 기록은 이벤트마다 externalizer 행 하나이고 전송이 끝나면 완료된다`() {
        val orderId = checkoutApproved()

        awaitUntil("발행 기록 넷이 모두 완료된다") {
            publications(orderId).let { rows -> rows.size == 4 && rows.all { it["completion_date"] != null } }
        }
        publications(orderId).forEach {
            assertEquals(
                "org.springframework.modulith.events.support.EventExternalizerModuleListener.externalize(java.lang.Object)",
                it["listener_id"],
            )
        }
    }

    @Test
    fun `기동하면 토픽과 DLT가 설정한 파티션 수로 생긴다`() {
        val counts = KafkaTestSupport.topicPartitionCounts()

        (topics + topics.map { "$it.DLT" }).forEach { assertEquals(1, counts[it], "$it 파티션 수") }
        // DLT는 사람이 볼 때까지 남아야 해서 보존 기간이 없다. 원 토픽은 브로커 기본값을 따른다.
        topics.forEach { assertEquals("-1", KafkaTestSupport.topicConfig("$it.DLT", "retention.ms"), "$it.DLT 보존 기간") }
    }

    @Test
    fun `consumer가 듣는 토픽은 모두 설정 목록에 있어 앱이 만든다`() {
        val listened = listenerRegistry.allListenerContainers.flatMap { it.containerProperties.topics.orEmpty().toList() }

        assertEquals(topics, listened.toSet())
        assertEquals(configuredTopics.toSet(), listened.toSet(), "설정 목록(resilientpos.kafka.topics)")
    }

    @Test
    fun `같은 이벤트를 두 번 받아도 시도와 PAID 전이와 fulfillment는 하나다`() {
        val orderId = checkoutApproved()
        awaitUntil("fulfillment가 생긴다") { count("select count(*) from t_fulfillment where order_id = ?", orderId) == 1L }

        topics.forEach { topic ->
            KafkaTestSupport.records(topic, orderId).forEach {
                kafkaTemplate.send(ProducerRecord(topic, it.key(), it.value().toByteArray())).get()
            }
        }
        groups.forEach { (topic, group) -> KafkaTestSupport.awaitConsumed(group, topic) }

        assertEquals(1L, count("select count(*) from t_payment_attempt where order_id = ?", orderId))
        assertEquals(1L, count("select count(*) from t_fulfillment where order_id = ?", orderId))
        assertEquals("PAID", jdbcTemplate.orderStatus(orderId))
    }

    @Test
    fun `타입 헤더 없이 온 메시지도 토픽의 이벤트 타입으로 읽는다`() {
        val orderId = UUID.randomUUID()
        val event = OrderPaid(
            eventId = UUID.randomUUID(),
            occurredAt = Instant.now(),
            orderId = orderId,
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            amount = Money.of("4500", OrderFixtures.krw),
        )

        val record = ProducerRecord<Any, Any>("order.paid", orderId.toString(), jsonMapper.writeValueAsBytes(event))
        kafkaTemplate.send(record).get()

        awaitUntil("fulfillment가 생긴다") { count("select count(*) from t_fulfillment where order_id = ?", orderId) == 1L }
        assertTrue(record.headers().toArray().isEmpty())
    }
}
