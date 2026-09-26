package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderFixtures
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 같은 checkout의 `createAttempt`가 겹칠 때 앞 시도가 거절까지 가도 시도가 하나인지 본다.
 *
 * 뒤쪽(B)을 바깥 트랜잭션에 넣어 조회·삽입을 마친 채 커밋을 붙잡고, 그동안 앞쪽(A)을 다른 스레드에서 부른다.
 * 잠금이 없으면 A가 커밋되고 Fake가 거절한 뒤 B가 커밋된다 — partial unique가 DECLINED를 빼고 봐서 둘째 시도가 들어간다.
 * 잠금이 있으면 A가 advisory lock에서 기다린다. B는 둘 중 하나를 관측한 뒤에 커밋하므로 순서가 결정적이다.
 *
 * 시도는 Facade로 직접 만든다. 주문 이벤트로 만들면 리스너가 세 번째 호출자로 끼어든다.
 */
class PaymentAttemptCreationConcurrencyIntegrationTest(
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id, status from t_payment_attempt where order_id = ? and kind = 'AUTHORIZATION' order by id",
        orderId,
    )

    private fun waitingAdvisoryLocks(): Int =
        jdbcTemplate.queryForObject("select count(*) from pg_locks where locktype = 'advisory' and not granted", Int::class.java)!!

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("10초 안에 충족되지 않았다: $description")
            Thread.sleep(20)
        }
    }

    @Test
    fun `잠금 대기 한도는 잠금을 얻은 뒤 원래 값으로 돌아간다`() {
        // 트랜잭션 한정 설정이라 그대로 두면 호출자 트랜잭션의 이후 잠금까지 3초로 묶인다(§4.6 sync intake는 한 트랜잭션이다).
        transactions.executeWithoutResult {
            val before = lockTimeout()
            paymentManagement.createAttempt(
                PaymentCommand.CreateAttempt(
                    orderId = UUID.randomUUID(),
                    storeId = StoreId(UUID.randomUUID()),
                    terminalId = TerminalId(UUID.randomUUID()),
                    checkoutNo = 1,
                    amount = Money.of("4500", OrderFixtures.krw),
                ),
            )
            assertEquals(before, lockTimeout())
        }
    }

    private fun lockTimeout(): String =
        jdbcTemplate.queryForObject("select current_setting('lock_timeout')", String::class.java)!!

    @Test
    fun `같은 checkout의 생성이 겹쳐 앞 시도가 거절되어도 시도는 하나다`() {
        val orderId = UUID.randomUUID()
        // 4501원은 끝자리가 1이라 Fake가 거절한다.
        val command = PaymentCommand.CreateAttempt(
            orderId = orderId,
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            amount = Money.of("4501", OrderFixtures.krw),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val (b, a) = requireNotNull(
                transactions.execute {
                    val b = paymentManagement.createAttempt(command)
                    val a = executor.submit<PaymentCommand.CreateAttempt.Result> { paymentManagement.createAttempt(command) }
                    awaitUntil("A가 거절까지 가거나 잠금에서 기다린다. 현재: ${attempts(orderId)}") {
                        attempts(orderId).singleOrNull()?.get("status") == "DECLINED" || waitingAdvisoryLocks() > 0
                    }
                    b to a
                },
            )
            val aResult = a.get(10, TimeUnit.SECONDS)

            assertEquals(1, attempts(orderId).size, "승인 시도가 둘 생겼다: ${attempts(orderId)}")
            assertEquals(b.paymentId, aResult.paymentId)
        } finally {
            executor.shutdownNow()
        }
    }
}
