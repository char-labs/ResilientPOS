package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.sql.Timestamp
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.PessimisticLockingFailureException
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
    private val fixtureTotal = Money.of("3502", OrderFixtures.krw)

    private fun checkoutRowCount(orderId: OrderId): Long =
        jdbcTemplate.queryForObject("select count(*) from t_order_checkout where order_id = ?", Long::class.java, orderId.value) ?: 0L

    private fun storedStatus(orderId: OrderId): String? =
        jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, orderId.value)

    private fun paymentRequestedCount(events: ApplicationEvents, orderId: OrderId): Long =
        events.stream(OrderPaymentRequested::class.java).filter { it.orderId == orderId.value }.count()

    @Test
    fun `requestCheckout은 CHECKOUT_REQUESTED와 checkout 행을 저장하고 OrderPaymentRequested를 한 번 발행한다`(events: ApplicationEvents) {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId

        val result = facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        assertEquals(orderId, result.orderId)
        assertEquals(OrderStatus.CHECKOUT_REQUESTED, result.status)
        assertEquals(1, result.checkoutNo)
        assertEquals(fixtureTotal, result.amount)
        assertEquals("CHECKOUT_REQUESTED", storedStatus(orderId))
        assertEquals(1L, checkoutRowCount(orderId))
        assertEquals(1L, paymentRequestedCount(events, orderId))
    }

    @Test
    fun `requestCheckout으로 주문 행이 갱신되면 생성 시각은 그대로이고 수정 시각만 새로 찍힌다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        // 시계에 기대지 않으려고 두 시각을 먼 과거로 돌려놓는다. version은 건드리지 않는다.
        val past = Timestamp.valueOf("2000-01-01 00:00:00")
        jdbcTemplate.update("update t_order set created_at = ?, updated_at = ? where id = ?", past, past, orderId.value)

        facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        val row = jdbcTemplate.queryForMap("select created_at, updated_at from t_order where id = ?", orderId.value)
        assertEquals(past, row["created_at"])
        assertTrue((row["updated_at"] as Timestamp).after(past), "updated_at=${row["updated_at"]}")
    }

    @Test
    fun `소수 자리가 있는 통화의 확정 금액도 저장하고 다시 읽으면 같은 값이다`() {
        val usd = Currency.getInstance("USD")
        val command = OrderFixtures.createOrderCommand(
            currency = usd,
            lines = listOf(OrderCommand.Create.Line("SKU-1", "Americano", Money.of("10.10", usd), 3)),
        )
        val orderId = facade.create(command).orderId

        val result = facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        assertEquals(Money.of("30.30", usd), result.amount)
        assertEquals(Money.of("30.30", usd), assertNotNull(repository.findById(orderId)).checkouts.single().amount)
    }

    @Test
    fun `checkout을 요청한 주문을 다시 읽으면 checkouts가 그대로 복원된다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

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
        val first = facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        val second = facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        assertEquals(OrderStatus.CHECKOUT_REQUESTED, second.status)
        assertEquals(first, second)
        assertEquals(1L, checkoutRowCount(orderId))
        assertEquals(1L, paymentRequestedCount(events, orderId))
    }

    @Test
    fun `없는 주문의 requestCheckout은 ORDER_NOT_FOUND다`() {
        val failure = assertFailsWith<DomainException> {
            facade.requestCheckout(OrderCommand.RequestCheckout(OrderId(UUID.randomUUID())))
        }

        assertEquals(Order.ORDER_NOT_FOUND, failure.code)
        assertEquals(DomainErrorKind.NOT_FOUND, failure.kind)
    }

    /** 다른 세션이 쥔 잠금을 기다리는 세션 수. 테스트는 한 번에 한 클래스만 돌므로 이 테스트가 만든 대기만 잡힌다. */
    private fun sessionsWaitingForLock(): Long =
        jdbcTemplate.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Long::class.java) ?: 0L

    @Test
    fun `커밋 전인 requestCheckout과 겹친 요청은 기다렸다가 같은 결과를 받는다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val command = OrderCommand.RequestCheckout(orderId)
        val firstRequested = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<OrderCommand.RequestCheckout.Result> {
                transaction.execute {
                    facade.requestCheckout(command).also {
                        firstRequested.countDown()
                        check(releaseFirst.await(30, TimeUnit.SECONDS)) { "앞 트랜잭션을 풀어 주는 신호가 오지 않았다" }
                    }
                }
            }
            assertTrue(firstRequested.await(10, TimeUnit.SECONDS), "앞 요청이 잠금을 쥔 채 커밋 전 상태에 도달해야 한다")

            val second = executor.submit<OrderCommand.RequestCheckout.Result> { facade.requestCheckout(command) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (sessionsWaitingForLock() == 0L && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(1L, sessionsWaitingForLock(), "뒤 요청이 주문 행 잠금을 기다리고 있어야 한다")
            assertFalse(second.isDone, "앞 트랜잭션이 커밋하기 전에는 끝나지 않아야 한다")

            releaseFirst.countDown()
            val firstResult = first.get(10, TimeUnit.SECONDS)
            val secondResult = second.get(10, TimeUnit.SECONDS)

            assertEquals(OrderStatus.CHECKOUT_REQUESTED, secondResult.status)
            assertEquals(firstResult, secondResult)
            assertEquals(1L, checkoutRowCount(orderId))
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `잠금을 3초 넘게 기다린 요청은 잠금 실패로 끝나고 다시 요청하면 기존 결과를 받는다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val command = OrderCommand.RequestCheckout(orderId)
        val firstRequested = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<OrderCommand.RequestCheckout.Result> {
                transaction.execute {
                    facade.requestCheckout(command).also {
                        firstRequested.countDown()
                        check(releaseFirst.await(30, TimeUnit.SECONDS)) { "앞 트랜잭션을 풀어 주는 신호가 오지 않았다" }
                    }
                }
            }
            assertTrue(firstRequested.await(10, TimeUnit.SECONDS), "앞 요청이 잠금을 쥔 채 커밋 전 상태에 도달해야 한다")

            val second = executor.submit<OrderCommand.RequestCheckout.Result> { facade.requestCheckout(command) }
            val failure = assertFailsWith<ExecutionException>("앞 트랜잭션이 잠금을 쥐고 있어도 무기한 기다리지 않아야 한다") {
                second.get(8, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is PessimisticLockingFailureException, "cause=${failure.cause}")

            releaseFirst.countDown()
            val firstResult = first.get(10, TimeUnit.SECONDS)

            assertEquals(1L, checkoutRowCount(orderId))
            assertEquals(firstResult, facade.requestCheckout(command))
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `checkout을 요청한 주문의 생성 재시도는 CHECKOUT_REQUESTED를 돌려준다`() {
        val createCommand = OrderFixtures.createOrderCommand()
        val orderId = facade.create(createCommand).orderId
        facade.requestCheckout(OrderCommand.RequestCheckout(orderId))

        val retried = facade.create(createCommand)

        assertEquals(orderId, retried.orderId)
        assertEquals(OrderStatus.CHECKOUT_REQUESTED, retried.status)
    }
}
