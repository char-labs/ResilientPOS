package com.resilientpos.platform

import com.resilientpos.KafkaTestSupport
import com.resilientpos.awaitUntil
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.Money
import com.resilientpos.fulfillment.internal.application.FulfillmentCommand
import com.resilientpos.fulfillment.internal.application.FulfillmentCommandFacade
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.orderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.sql.SQLException
import org.hibernate.TransactionException as HibernateTransactionException
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.support.TransactionOperations

/**
 * consumer가 실패할 때 재시도와 DLT. 실패는 Facade spy가 지정한 주문에만 주입하고 다른 주문은 실제 동작을 탄다.
 * 재시도 간격은 테스트 기반 클래스가 짧게 바꾸고 횟수는 운영 기본값(5회)을 그대로 쓴다.
 */
class KafkaRetryIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    @Autowired private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Autowired private val meterRegistry: MeterRegistry,
) : PostgresIntegrationTest() {

    @MockitoSpyBean
    private lateinit var fulfillmentFacade: FulfillmentCommandFacade

    @MockitoSpyBean
    private lateinit var spiedOrderFacade: OrderCommandFacade

    /** 주문별로 남은 실패 횟수와 던질 예외. */
    private val failuresLeft = ConcurrentHashMap<UUID, AtomicInteger>()
    private val failure = ConcurrentHashMap<UUID, () -> Exception>()
    private val fulfillmentCalls = ConcurrentHashMap<UUID, AtomicInteger>()
    private val markPaidCalls = ConcurrentHashMap<UUID, AtomicInteger>()

    @BeforeTest
    fun injectFailures() {
        doAnswer { invocation ->
            val orderId = invocation.getArgument<FulfillmentCommand.Create>(0).orderId
            fulfillmentCalls.computeIfAbsent(orderId) { AtomicInteger() }.incrementAndGet()
            val left = failuresLeft[orderId]
            if (left != null && left.getAndDecrement() != 0) {
                throw failure[orderId]?.invoke() ?: TransientDataAccessResourceException("테스트용 일시 장애")
            }
            invocation.callRealMethod()
        }.`when`(fulfillmentFacade).createFulfillment(anyOf(FulfillmentCommand.Create::class.java))
        doAnswer { invocation ->
            markPaidCalls.computeIfAbsent(invocation.getArgument<OrderCommand.MarkPaid>(0).orderId.value) { AtomicInteger() }.incrementAndGet()
            invocation.callRealMethod()
        }.`when`(spiedOrderFacade).markPaid(anyOf(OrderCommand.MarkPaid::class.java))
    }

    /** Mockito의 `any`는 null을 돌려주어 Kotlin의 non-null 검사에 걸린다. 매처만 등록하고 검사를 건너뛴다. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(type: Class<T>): T {
        any(type)
        return null as T
    }

    private fun fulfillmentCount(orderId: UUID) =
        jdbcTemplate.queryForObject<Long>("select count(*) from t_fulfillment where order_id = ?", orderId)

    private fun deadLetters(topic: String, reason: String = "error") =
        meterRegistry.find("event.kafka.dead-letter").tag("topic", topic).tag("reason", reason).counter()?.count() ?: 0.0

    /**
     * 4500원은 끝자리가 0이라 FakePaymentGateway가 승인해 order.paid까지 간다.
     * fulfillment 실패는 checkout 전에 주문 ID로 건다. 음수면 끝까지 실패한다.
     */
    private fun checkoutApproved(failures: Int? = null, exception: (() -> Exception)? = null): UUID {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("4500", OrderFixtures.krw), 1)),
            ),
        )
        failures?.let { failuresLeft[created.orderId.value] = AtomicInteger(it) }
        exception?.let { failure[created.orderId.value] = it }
        orderFacade.requestCheckout(OrderCommand.RequestCheckout(created.orderId))
        return created.orderId.value
    }

    @Test
    fun `일시 장애 뒤 성공하면 DLT로 가지 않고 한 번만 반영된다`() {
        val orderId = checkoutApproved(failures = 2)

        awaitUntil("fulfillment가 생긴다") { fulfillmentCount(orderId) == 1L }
        assertEquals(3, fulfillmentCalls.getValue(orderId).get())
        KafkaTestSupport.awaitConsumed("fulfillment.order-paid", "order.paid")
        assertEquals(emptyList(), KafkaTestSupport.records("order.paid.DLT", orderId))
    }

    @Test
    fun `DB에 닿지 못하는 실패는 재시도 횟수를 넘겨도 기다렸다가 처리한다`() {
        val orderId = checkoutApproved(failures = 8) { CannotCreateTransactionException("테스트용 커넥션 획득 실패") }

        awaitUntil("fulfillment가 생긴다") { fulfillmentCount(orderId) == 1L }
        assertEquals(9, fulfillmentCalls.getValue(orderId).get(), "운영 재시도 횟수(5)를 넘긴 여덟 번 실패 뒤 성공")
        KafkaTestSupport.awaitConsumed("fulfillment.order-paid", "order.paid")
        assertEquals(emptyList(), KafkaTestSupport.records("order.paid.DLT", orderId))
    }

    /**
     * 처리 도중 DB 연결이 끊기면(failover 순간의 관리자 종료, SQLState 57P01) 원래 예외는 롤백 실패에 덮여
     * 리스너에는 이 모양으로 온다. 2026-09-27 `pg_terminate_backend`로 실측한 체인을 그대로 만든다.
     */
    private fun connectionLostDuringTransaction() =
        JpaSystemException(HibernateTransactionException("Unable to rollback against JDBC Connection", SQLException("Connection is closed")))

    @Test
    fun `처리 도중 DB 연결이 끊겨 롤백까지 실패해도 DLT로 보내지 않고 다시 시도한다`() {
        val orderId = checkoutApproved(failures = 2) { connectionLostDuringTransaction() }

        awaitUntil("fulfillment가 생긴다") { fulfillmentCount(orderId) == 1L }
        assertEquals(3, fulfillmentCalls.getValue(orderId).get())
        KafkaTestSupport.awaitConsumed("fulfillment.order-paid", "order.paid")
        assertEquals(emptyList(), KafkaTestSupport.records("order.paid.DLT", orderId))
    }

    @Test
    fun `재시도 허용 목록 밖의 예외는 재시도 없이 DLT로 간다`() {
        val orderId = checkoutApproved(failures = -1) { IllegalStateException("테스트용 버그") }

        awaitUntil("DLT에 들어간다") { KafkaTestSupport.records("order.paid.DLT", orderId).size == 1 }
        assertEquals(1, fulfillmentCalls.getValue(orderId).get())
    }

    @Test
    fun `재시도를 모두 실패하면 DLT로 가고 다음 메시지가 처리된다`() {
        val before = deadLetters("order.paid")
        val failing = checkoutApproved(failures = -1)
        val next = checkoutApproved()

        awaitUntil("뒤 주문의 fulfillment가 생긴다") { fulfillmentCount(next) == 1L }
        awaitUntil("DLT에 원래 key로 들어간다") { KafkaTestSupport.records("order.paid.DLT", failing).size == 1 }
        assertEquals(0L, fulfillmentCount(failing))
        assertEquals(6, fulfillmentCalls.getValue(failing).get(), "처음 한 번과 재시도 다섯 번")
        assertEquals(before + 1, deadLetters("order.paid"))
    }

    @Test
    fun `DomainException은 재시도 없이 DLT로 가고 도메인 코드로 세며 주문은 그대로다`() {
        val before = deadLetters("payment.approved", "PAYMENT_APPROVAL_MISMATCH")
        // 픽스처 합계 3502원은 결과가 미확인이라 진짜 승인이 오지 않는다. 확정값과 다른 승인을 직접 낸다.
        val created = orderFacade.create(OrderFixtures.createOrderCommand())
        orderFacade.requestCheckout(OrderCommand.RequestCheckout(created.orderId))
        val orderId = created.orderId.value
        transactions.executeWithoutResult {
            eventPublisher.publishEvent(
                PaymentApproved(
                    eventId = UUID.randomUUID(),
                    occurredAt = Instant.now(),
                    paymentId = UUID.randomUUID(),
                    orderId = orderId,
                    storeId = StoreId(UUID.randomUUID()),
                    terminalId = TerminalId(UUID.randomUUID()),
                    checkoutNo = 1,
                    kind = PaymentAttemptKind.AUTHORIZATION,
                    amount = Money.of("9999", OrderFixtures.krw),
                    providerReference = null,
                ),
            )
        }

        awaitUntil("DLT에 들어간다") { KafkaTestSupport.records("payment.approved.DLT", orderId).size == 1 }
        assertEquals(1, markPaidCalls.getValue(orderId).get())
        assertEquals(before + 1, deadLetters("payment.approved", "PAYMENT_APPROVAL_MISMATCH"))
        assertEquals(
            "CHECKOUT_REQUESTED",
            jdbcTemplate.orderStatus(orderId),
        )
    }

    @Test
    fun `역직렬화할 수 없는 값은 consumer를 멈추지 않고 DLT로 간다`() {
        val key = UUID.randomUUID()
        kafkaTemplate.send("order.paid", key.toString(), "not-json".toByteArray()).get(5, TimeUnit.SECONDS)
        val next = checkoutApproved()

        awaitUntil("DLT에 들어간다") { KafkaTestSupport.records("order.paid.DLT", key).size == 1 }
        awaitUntil("뒤 주문의 fulfillment가 생긴다") { fulfillmentCount(next) == 1L }
    }
}
