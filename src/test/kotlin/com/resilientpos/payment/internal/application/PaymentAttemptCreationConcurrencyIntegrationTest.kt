package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderFixtures
import com.resilientpos.payment.PaymentFixtures
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 같은 checkout의 `createAttempt`가 겹칠 때 앞 시도가 거절까지 가도 시도가 하나인지 본다.
 *
 * 뒤쪽(B)은 조회를 마치고 넣기만 남은 요청이다. 바깥 트랜잭션에서 repository로 직접 넣어 커밋을 붙잡고, 그동안 앞쪽(A)을 다른 스레드에서 끝까지 커밋시킨다.
 * B를 Facade로 부르지 않는 이유: `createAttempt`는 락 블록의 새 트랜잭션에서 곧바로 커밋하므로 커밋을 붙잡을 수 없다.
 * A가 거절된 뒤 B를 커밋한다. 진행 중인 승인만 보는 partial unique는 DECLINED를 빼고 봐서 B를 막지 못한다.
 * B의 삽입은 커밋에서 flush되므로 A는 B를 기다리지 않는다 — 순서가 결정적이다. B 트랜잭션 안에 JPQL을 두면 auto-flush로
 * 이 전제가 깨진다. B 안의 폴링이 A의 커밋을 보는 것은 READ COMMITTED 전제다.
 *
 * A는 Facade로 직접 만든다. 주문 이벤트로 만들면 리스너가 세 번째 호출자로 끼어든다.
 *
 * B는 락을 거치지 않고 repository로 직접 넣으므로 분산 락은 결과에 관여하지 않는다. 이 테스트는 잠금이 통하지 않을 때(Redis 장애·대기 초과) DB 방어선만 보는 것이다.
 */
class PaymentAttemptCreationConcurrencyIntegrationTest(
    private val paymentManagement: PaymentManagement,
    private val transactions: TransactionOperations,
    private val jdbcTemplate: JdbcTemplate,
    private val repository: PaymentAttemptRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) : PostgresIntegrationTest() {

    private fun attempts(orderId: UUID): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "select id, status from t_payment_attempt where order_id = ? and kind = 'AUTHORIZATION' order by id",
        orderId,
    )

    @Test
    fun `같은 checkout의 생성이 겹쳐 앞 시도가 거절되어도 시도는 하나다`() {
        val orderId = UUID.randomUUID()
        // 4501원은 끝자리가 1이라 Fake가 거절한다.
        val command = PaymentFixtures.createAttemptCommand(
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
                    repository.save(PaymentAttempt.create(command, identifierGenerator, clock.instant()).aggregate)
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
