package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderFixtures
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.domain.CheckoutLock
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 같은 checkout의 `createAttempt`가 겹칠 때 앞 시도가 거절까지 가도 시도가 하나인지 본다.
 *
 * 뒤쪽(B)을 바깥 트랜잭션에 넣어 조회를 마친 채 커밋을 붙잡고, 그동안 앞쪽(A)을 다른 스레드에서 끝까지 커밋시킨다.
 * A가 거절된 뒤 B를 커밋한다. 진행 중인 승인만 보는 partial unique는 DECLINED를 빼고 봐서 B를 막지 못한다.
 * B의 삽입은 커밋에서 flush되므로 A는 B를 기다리지 않는다 — 순서가 결정적이다. B 트랜잭션 안에 JPQL을 두면 auto-flush로
 * 이 전제가 깨진다. B 안의 폴링이 A의 커밋을 보는 것은 READ COMMITTED 전제다.
 *
 * 시도는 Facade로 직접 만든다. 주문 이벤트로 만들면 리스너가 세 번째 호출자로 끼어든다.
 *
 * Redis 잠금은 빈 구현으로 바꾼다. 이 테스트는 잠금이 통하지 않을 때(Redis 장애·대기 초과) DB 방어선만 보는 것이다.
 */
class PaymentAttemptCreationConcurrencyIntegrationTest(
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    @TestConfiguration
    class NoCheckoutLockConfiguration {
        @Bean
        @Primary
        fun noCheckoutLock() = object : CheckoutLock {
            override fun lockUntilTransactionEnds(orderId: UUID, checkoutNo: Int) = Unit
        }
    }

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id, status from t_payment_attempt where order_id = ? and kind = 'AUTHORIZATION' order by id",
        orderId,
    )

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("10초 안에 충족되지 않았다: $description")
            Thread.sleep(20)
        }
    }

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
            val lost = assertFailsWith<DataIntegrityViolationException> {
                transactions.executeWithoutResult {
                    paymentManagement.createAttempt(command)
                    executor.submit { paymentManagement.createAttempt(command) }.get(10, TimeUnit.SECONDS)
                    awaitUntil("A가 거절까지 간다. 현재: ${attempts(orderId)}") {
                        attempts(orderId).singleOrNull()?.get("status") == "DECLINED"
                    }
                }
            }

            // 진행 중 승인 인덱스가 아니라 첫 승인 시도 인덱스에 걸려야 이 테스트가 닫으려는 창을 본 것이다.
            assertContains(lost.mostSpecificCause.message.orEmpty(), "ux_payment_attempt_first_authorization")
            assertEquals(1, attempts(orderId).size, "승인 시도가 둘 생겼다: ${attempts(orderId)}")
        } finally {
            executor.shutdownNow()
        }
    }
}
