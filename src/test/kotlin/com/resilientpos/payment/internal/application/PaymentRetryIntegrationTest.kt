package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.order.orderStatus
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentFixtures
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.ScriptedPaymentGateway
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.store.StoreId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionOperations

/**
 * 거절된 결제를 같은 checkout 회차에서 다시 시도해 주문이 결제되기까지를 끝까지 태운다.
 *
 * [FakePaymentGateway]는 금액으로만 결말을 정해서 같은 회차(같은 금액)의 재결제가 늘 다시 거절된다.
 * 그래서 이 테스트는 공용 [ScriptedPaymentGateway]에 주문 금액마다 **처음 한 번은 거절하고 그다음은 승인하라고** 건다. 테스트마다 금액이 달라야 한다.
 */
class PaymentRetryIntegrationTest(
    private val orderFacade: OrderCommandFacade,
    private val paymentManagement: PaymentManagement,
    private val jdbcTemplate: JdbcTemplate,
    private val transactions: TransactionOperations,
    private val repository: PaymentAttemptRepository,
    private val gateway: ScriptedPaymentGateway,
) : PostgresIntegrationTest() {

    private fun checkoutOrder(unitPrice: String): UUID {
        gateway.declineFirstThenApprove(Money.of(unitPrice, OrderFixtures.krw))
        return orderFacade.createAndRequestCheckout(OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice)))).value
    }

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id, checkout_no, status from t_payment_attempt where order_id = ? order by id",
        orderId,
    )

    private fun awaitDeclined(orderId: UUID): PaymentId {
        awaitUntil("첫 시도가 거절된다. 현재: ${attempts(orderId)}") {
            attempts(orderId).singleOrNull()?.get("status") == PaymentAttemptStatus.DECLINED.name
        }
        return PaymentId(attempts(orderId).single()["id"] as UUID)
    }

    @Test
    fun `거절 뒤 같은 회차에서 다시 시도해 승인되면 주문이 PAID가 된다`() {
        val orderId = checkoutOrder("4701")
        val declined = awaitDeclined(orderId)

        val retried = paymentManagement.retryAuthorization(PaymentFixtures.retryAuthorizationCommand(declined, OrderFixtures.storeId))

        awaitUntil("주문이 PAID가 된다. 현재: ${jdbcTemplate.orderStatus(orderId)} ${attempts(orderId)}") {
            jdbcTemplate.orderStatus(orderId) == "PAID"
        }
        val rows = attempts(orderId)
        assertEquals(listOf(declined.value, retried.paymentId.value), rows.map { it["id"] })
        assertEquals(listOf(1, 1), rows.map { it["checkout_no"] })
        assertEquals(listOf("DECLINED", "APPROVED"), rows.map { it["status"] })
    }

    @Test
    fun `같은 거절 시도에 재결제가 겹쳐도 새 시도는 하나다`() {
        val orderId = checkoutOrder("4703")
        val declined = awaitDeclined(orderId)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            List(2) {
                executor.submit<PaymentCommand.RetryAuthorization.Result> {
                    ready.countDown()
                    ready.await(5, TimeUnit.SECONDS)
                    paymentManagement.retryAuthorization(PaymentFixtures.retryAuthorizationCommand(declined, OrderFixtures.storeId))
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.map { it.paymentId }.distinct().size, "두 호출이 서로 다른 시도를 돌려받았다: $results")
        assertEquals(2, attempts(orderId).size)
    }

    @Test
    fun `다른 매장의 재결제는 시도 행이 잠겨 있어도 기다리지 않고 PAYMENT_ATTEMPT_NOT_FOUND를 받는다`() {
        val orderId = checkoutOrder("4707")
        val declined = awaitDeclined(orderId)
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                transactions.executeWithoutResult {
                    repository.findByIdForUpdate(declined)
                    lockHeld.countDown()
                    check(releaseLock.await(30, TimeUnit.SECONDS)) { "잠금을 풀어 주는 신호가 오지 않았다" }
                }
            }
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "다른 트랜잭션이 시도 행을 잠가야 한다")

            val other = executor.submit<DomainException> {
                assertFailsWith<DomainException> {
                    paymentManagement.retryAuthorization(PaymentFixtures.retryAuthorizationCommand(declined, StoreId(UUID.randomUUID())))
                }
            }
            // 매장 조건으로 잠그면 잠긴 행이 조회에 걸리지 않아 즉시 끝난다. 잠금 대기 한도는 3초다.
            val failure = other.get(2, TimeUnit.SECONDS)

            assertEquals(PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND, failure.code)
            assertEquals(DomainErrorKind.NOT_FOUND, failure.kind)
            assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from pg_stat_activity where wait_event_type = 'Lock'"))
            releaseLock.countDown()
            holder.get(10, TimeUnit.SECONDS)
            assertEquals(1, attempts(orderId).size)
        } finally {
            releaseLock.countDown()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `트랜잭션 안에서 재결제를 요청해도 바깥 트랜잭션과 따로 커밋한다`() {
        val orderId = checkoutOrder("4705")
        val declined = awaitDeclined(orderId)

        // 바깥 트랜잭션을 멈추고 새 시도를 따로 커밋하므로, 바깥을 롤백해도 새 시도는 남는다.
        transactions.executeWithoutResult { outer ->
            paymentManagement.retryAuthorization(PaymentFixtures.retryAuthorizationCommand(declined, OrderFixtures.storeId))
            outer.setRollbackOnly()
        }

        assertEquals(2, attempts(orderId).size, "새 시도가 바깥 롤백에 휩쓸렸다: ${attempts(orderId)}")
    }
}
