package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderPaymentRequested
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.RequestCheckoutCommand
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate

/**
 * `OrderCommandFacade.requestCheckout`이 전이와 checkout 행을 한 트랜잭션으로 저장하는지 검사한다. Docker가 필요하다.
 */
@RecordApplicationEvents
class OrderRequestCheckoutIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val repository: OrderRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transaction: TransactionTemplate,
) : PostgresIntegrationTest() {

    // OrderFixtures의 기본 line: 1000 x 2 + 500 x 3
    private val fixtureTotal = Money.of("3500", OrderFixtures.krw)

    private fun checkoutRowCount(orderId: OrderId): Long =
        jdbcTemplate.queryForObject("select count(*) from t_order_checkout where order_id = ?", Long::class.java, orderId.value) ?: 0L

    private fun storedStatus(orderId: OrderId): String? =
        jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, orderId.value)

    private fun paymentRequestedCount(events: ApplicationEvents, orderId: OrderId): Long =
        events.stream(OrderPaymentRequested::class.java).filter { it.orderId == orderId }.count()

    @Test
    fun `requestCheckout은 CHECKOUT_REQUESTED와 checkout 행을 저장하고 OrderPaymentRequested를 한 번 발행한다`(events: ApplicationEvents) {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId

        val result = facade.requestCheckout(RequestCheckoutCommand(orderId))

        assertEquals(orderId, result.orderId)
        assertEquals(OrderStatus.CHECKOUT_REQUESTED, result.status)
        assertEquals(1, result.checkoutNo)
        assertEquals(fixtureTotal, result.amount)
        assertEquals("CHECKOUT_REQUESTED", storedStatus(orderId))
        assertEquals(1L, checkoutRowCount(orderId))
        assertEquals(1L, paymentRequestedCount(events, orderId))
    }

    @Test
    fun `checkout을 요청한 주문을 다시 읽으면 checkouts가 그대로 복원된다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        facade.requestCheckout(RequestCheckoutCommand(orderId))

        val checkout = assertNotNull(repository.findById(orderId)).checkouts.single()

        assertEquals(1, checkout.checkoutNo)
        assertEquals(fixtureTotal, checkout.amount)
        assertEquals(
            checkout.id.value,
            jdbcTemplate.queryForObject("select id from t_order_checkout where order_id = ?", UUID::class.java, orderId.value),
        )
    }

    @Test
    fun `같은 주문의 requestCheckout 재요청은 기존 결과를 돌려주고 행과 이벤트를 늘리지 않는다`(events: ApplicationEvents) {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val first = facade.requestCheckout(RequestCheckoutCommand(orderId))

        val second = facade.requestCheckout(RequestCheckoutCommand(orderId))

        assertEquals(OrderStatus.CHECKOUT_REQUESTED, second.status)
        assertEquals(first, second)
        assertEquals(1L, checkoutRowCount(orderId))
        assertEquals(1L, paymentRequestedCount(events, orderId))
    }

    @Test
    fun `없는 주문의 requestCheckout은 ORDER_NOT_FOUND다`() {
        val failure = assertFailsWith<DomainException> {
            facade.requestCheckout(RequestCheckoutCommand(OrderId(UUID.randomUUID())))
        }

        assertEquals(Order.ORDER_NOT_FOUND, failure.code)
        assertEquals(DomainErrorKind.NOT_FOUND, failure.kind)
    }

    @Test
    fun `커밋 전인 requestCheckout과 겹친 요청은 기다렸다가 같은 결과를 받는다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val command = RequestCheckoutCommand(orderId)
        val firstRequested = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<com.resilientpos.order.RequestCheckoutResult> {
                transaction.execute {
                    facade.requestCheckout(command).also {
                        firstRequested.countDown()
                        releaseFirst.await(10, TimeUnit.SECONDS)
                    }
                }
            }
            assertTrue(firstRequested.await(10, TimeUnit.SECONDS))

            val second = executor.submit<com.resilientpos.order.RequestCheckoutResult> { facade.requestCheckout(command) }
            assertFailsWith<TimeoutException>("앞 트랜잭션이 커밋하기 전에는 끝나지 않아야 한다") { second.get(1, TimeUnit.SECONDS) }

            releaseFirst.countDown()
            val firstResult = first.get(10, TimeUnit.SECONDS)
            val secondResult = second.get(10, TimeUnit.SECONDS)

            assertEquals(OrderStatus.CHECKOUT_REQUESTED, secondResult.status)
            assertEquals(firstResult, secondResult)
            assertEquals(1L, checkoutRowCount(orderId))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `checkout을 요청한 주문의 생성 재시도는 CHECKOUT_REQUESTED를 돌려준다`() {
        val createCommand = OrderFixtures.createOrderCommand()
        val orderId = facade.create(createCommand).orderId
        facade.requestCheckout(RequestCheckoutCommand(orderId))

        val retried = facade.create(createCommand)

        assertEquals(orderId, retried.orderId)
        assertEquals(OrderStatus.CHECKOUT_REQUESTED, retried.status)
    }
}
