package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderFixtures
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.platform.Lock
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 같은 checkout의 `createAttempt`가 겹치면 늦게 온 요청이 락에서 앞 요청의 커밋을 기다렸다가
 * 그 시도를 돌려받고 예외 없이 끝나는지 본다. 여러 pod가 같은 사실을 동시에 처리하는 상황이다.
 *
 * 앞 요청을 락 블록으로 흉내 낸다. 블록 안에서 시도를 넣고, 그동안 늦은 요청을 다른 스레드에서 부른다.
 * 락이 없으면 늦은 요청은 시도를 찾지 못하고 넣다가 unique에 걸려 실패한다.
 */
class PaymentAttemptCreationLockIntegrationTest(
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val repository: PaymentAttemptRepository,
    @Autowired private val identifierGenerator: IdentifierGenerator,
    @Autowired private val clock: Clock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
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
            lateinit var late: Future<PaymentCommand.CreateAttempt.Result>
            val first = Lock.executeWithLock(PaymentManagement.checkoutLockKey(orderId, 1)) {
                val saved = repository.save(PaymentAttempt.create(command, identifierGenerator, clock.instant()).aggregate)
                late = executor.submit<PaymentCommand.CreateAttempt.Result> { paymentManagement.createAttempt(command) }
                runCatching { late.get(500, TimeUnit.MILLISECONDS) }
                assertFalse(late.isDone, "늦은 요청이 락을 기다리지 않았다")
                saved
            }

            assertEquals(first.id, late.get(5, TimeUnit.SECONDS).paymentId)
            assertEquals(1, attempts(orderId).size)
        } finally {
            executor.shutdownNow()
        }
    }
}
