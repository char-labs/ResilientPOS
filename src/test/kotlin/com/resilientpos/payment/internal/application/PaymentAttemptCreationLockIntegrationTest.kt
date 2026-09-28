package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderFixtures
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.domain.CheckoutLock
import com.resilientpos.platform.inTransaction
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 같은 checkout의 `createAttempt`가 겹치면 늦게 온 요청이 Redis 잠금에서 앞 요청의 커밋을 기다렸다가
 * 그 시도를 돌려받고 예외 없이 끝나는지 본다. 여러 pod가 같은 사실을 동시에 처리하는 상황이다.
 *
 * 앞 요청(B)을 바깥 트랜잭션에 넣어 커밋을 붙잡고, 그동안 늦은 요청(A)을 다른 스레드에서 부른다.
 * A가 끝나거나 500ms가 지나면 B를 커밋한다. 잠금이 없으면 A가 먼저 커밋하고 B가 unique 위반으로 실패한다.
 * 잠금이 있으면 A는 3초까지 기다리므로 500ms 안에 끝나지 않고, B의 커밋 뒤 B의 시도를 돌려받는다.
 */
class PaymentAttemptCreationLockIntegrationTest(
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val checkoutLock: CheckoutLock,
) : PostgresIntegrationTest() {

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id from t_payment_attempt where order_id = ? and kind = 'AUTHORIZATION'",
        orderId,
    )

    @Test
    fun `같은 checkout의 생성이 겹치면 늦은 요청이 앞 요청의 시도를 돌려받는다`() {
        val orderId = UUID.randomUUID()
        val command = PaymentCommand.CreateAttempt(
            orderId = orderId,
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            amount = Money.of("4500", OrderFixtures.krw),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val (first, late) = transactions.inTransaction {
                val first = paymentManagement.createAttempt(command)
                val late = executor.submit<PaymentCommand.CreateAttempt.Result> { paymentManagement.createAttempt(command) }
                runCatching { late.get(500, TimeUnit.MILLISECONDS) }
                first to late
            }

            assertEquals(first.paymentId, late.get(5, TimeUnit.SECONDS).paymentId)
            assertEquals(1, attempts(orderId).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `트랜잭션 밖에서 잠그면 거절한다`() {
        // 해제가 트랜잭션 끝에 걸리므로 트랜잭션이 없으면 잠금이 TTL까지 남는다.
        assertFailsWith<IllegalStateException> {
            checkoutLock.lockUntilTransactionEnds(UUID.randomUUID(), 1)
        }
    }
}
