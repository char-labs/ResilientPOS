package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate

/**
 * Event Publication Registry가 이 저장소의 구성(PostgreSQL, Flyway 스키마, JPA validate, Jackson)에서 동작하는지 검사한다.
 * 프로덕션 리스너가 아직 없어서 이 클래스의 컨텍스트에만 테스트용 리스너를 넣는다. Docker가 필요하다.
 */
@Import(OrderEventPublicationIntegrationTest.ListenerConfiguration::class)
@RecordApplicationEvents
class OrderEventPublicationIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val listener: PaymentRequestedListener,
    @Autowired private val eventSerializer: EventSerializer,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transaction: TransactionTemplate,
) : PostgresIntegrationTest() {

    /**
     * Modulith의 자동 구성이 비동기 실행을 켜므로 커밋 뒤에 task executor의 스레드에서 실행된다.
     * 프록시가 걸리므로 클래스와 멤버가 모두 open이다. final 프로퍼티는 프록시의 빈 필드를 읽는다.
     */
    open class PaymentRequestedListener {
        open val received = CopyOnWriteArrayList<OrderPaymentRequested>()

        @Volatile
        open var failing = false

        @ApplicationModuleListener
        open fun on(event: OrderPaymentRequested) {
            received += event
            check(!failing) { "테스트용 리스너 실패" }
        }
    }

    @TestConfiguration
    class ListenerConfiguration {
        @Bean
        fun paymentRequestedListener() = PaymentRequestedListener()
    }

    @BeforeTest
    fun resetListener() {
        listener.failing = false
    }

    private fun publications(orderId: OrderId): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            """
            select event_type, serialized_event, completion_date, status from event_publication
            where serialized_event like ? and listener_id like ?
            """.trimIndent(),
            "%${orderId.value}%",
            // 이 테스트 전용 리스너만 센다. simpleName만으로 거르면 이름이 이것을 포함하는
            // 다른 리스너(OrderPaymentRequestedListener)까지 걸려서 프로덕션 리스너가 생길 때마다 깨진다.
            "%${PaymentRequestedListener::class.java.name}%",
        )

    private fun received(orderId: OrderId) = listener.received.filter { it.orderId == orderId.value }

    /** 리스너는 요청 스레드 밖에서 돌므로 결과가 기록될 때까지 기다린다. */
    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                fail("10초 안에 충족되지 않았다: $description. 발행 기록: " + jdbcTemplate.queryForList("select listener_id, status, completion_date from event_publication"))
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun `리스너가 성공하면 발행 기록이 완료로 표시된다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId

        facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        awaitUntil("발행 기록에 완료 시각이 찍힌다") { publications(orderId).singleOrNull()?.get("completion_date") != null }
        val publication = publications(orderId).single()
        // 클래스에서 기대값을 다시 계산하면 이벤트를 옮겨도 통과해 버린다. event_type은 저장 형식이므로 리터럴로 고정한다.
        assertEquals("com.resilientpos.contract.OrderPaymentRequested", publication["event_type"])
        assertEquals("COMPLETED", publication["status"])
        assertEquals(1, received(orderId).size)
    }

    @Test
    fun `리스너가 실패하면 업무 결과는 그대로이고 발행 기록은 미완료로 남는다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        listener.failing = true

        val result = facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        assertEquals(OrderStatus.CHECKOUT_REQUESTED, result.status)
        assertEquals(
            "CHECKOUT_REQUESTED",
            jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, orderId.value),
        )
        awaitUntil("리스너가 이벤트를 받고 실패가 기록된다") { publications(orderId).singleOrNull()?.get("status") == "FAILED" }
        assertNull(publications(orderId).single()["completion_date"])
        assertEquals(1, received(orderId).size)
    }

    @Test
    fun `업무 트랜잭션이 롤백되면 발행 기록도 리스너 호출도 없다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId

        transaction.executeWithoutResult { status ->
            facade.requestCheckout(OrderCommand.RequestCheckout(orderId))
            status.setRollbackOnly()
        }

        assertEquals(emptyList(), publications(orderId))

        // 시간에 기대지 않으려고 다른 주문의 이벤트가 끝까지 처리되는 것을 기다린다. 롤백된 이벤트가 전달됐다면 그 전에 도착했다.
        val otherOrderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        facade.requestCheckout(OrderCommand.RequestCheckout(otherOrderId))
        awaitUntil("뒤에 발행한 이벤트가 완료된다") { publications(otherOrderId).singleOrNull()?.get("completion_date") != null }
        assertEquals(emptyList(), received(orderId))
        assertEquals("OPEN", jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, orderId.value))
    }

    @Test
    fun `저장된 이벤트를 복원하면 발행한 값과 같다`(events: ApplicationEvents) {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        facade.requestCheckout(OrderCommand.RequestCheckout(orderId))
        awaitUntil("리스너가 이벤트를 받는다") { received(orderId).size == 1 }
        val published = received(orderId).single()

        val serialized = assertNotNull(publications(orderId).single()["serialized_event"])
        val restored = eventSerializer.deserialize(serialized, OrderPaymentRequested::class.java)

        assertEquals(published, restored)

        val created = events.stream(OrderCreated::class.java).filter { it.orderId == orderId }.findFirst().orElseThrow()
        assertEquals(created, eventSerializer.deserialize(eventSerializer.serialize(created), OrderCreated::class.java))
    }

    @Test
    fun `끝자리가 0인 소수 통화 금액도 자릿수를 잃지 않고 복원된다`() {
        val usd = Currency.getInstance("USD")
        val event = OrderPaymentRequested(
            eventId = UUID.randomUUID(),
            occurredAt = Instant.parse("2026-09-21T06:00:00Z"),
            orderId = UUID.randomUUID(),
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            amount = Money.of("30.00", usd),
        )

        val restored = eventSerializer.deserialize(eventSerializer.serialize(event), OrderPaymentRequested::class.java)

        assertEquals(event, restored)
        assertEquals(2, restored.amount.amount.scale())
    }

    @Test
    fun `자릿수가 통화와 어긋난 금액은 복원을 거절한다`() {
        val event = OrderPaymentRequested(
            eventId = UUID.randomUUID(),
            occurredAt = Instant.parse("2026-09-21T06:00:00Z"),
            orderId = UUID.randomUUID(),
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            amount = Money.of("30.00", Currency.getInstance("USD")),
        )
        val tampered = eventSerializer.serialize(event).toString().replace("30.00", "30.0")

        assertFails { eventSerializer.deserialize(tampered, OrderPaymentRequested::class.java) }
    }

    @Test
    fun `받는 리스너가 없는 이벤트는 기록되지 않는다`(events: ApplicationEvents) {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId

        assertEquals(1L, events.stream(OrderCreated::class.java).filter { it.orderId == orderId }.count())
        assertEquals(emptyList(), publications(orderId))
    }
}
