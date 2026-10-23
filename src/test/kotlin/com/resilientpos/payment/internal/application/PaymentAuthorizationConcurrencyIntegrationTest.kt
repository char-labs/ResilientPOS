package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.ScriptedPaymentGateway
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 같은 시도에 `authorize`가 겹칠 때 결과가 한 번만 적용되는지 본다.
 *
 * provider(공용 [ScriptedPaymentGateway])가 이 시도의 두 호출을 서로 기다리게 하고 **서로 다른** 답을 주게 만든다. 잠금이 없으면 둘 다 `REQUESTED`를 읽고 둘 다 적용해
 * 나중에 커밋한 쪽이 앞의 결과를 덮는다 — 승인이 거절로 조용히 바뀐다. 그러면 두 호출이 돌려받은 결말이
 * 서로 다르고 DB의 결말과도 어긋난다.
 *
 * 시도는 SQL로 직접 넣는다. 이벤트로 만들면 승인 리스너가 세 번째 호출자로 끼어든다.
 */
class PaymentAuthorizationConcurrencyIntegrationTest(
    private val paymentManagement: PaymentManagement,
    private val jdbcTemplate: JdbcTemplate,
    private val gateway: ScriptedPaymentGateway,
) : PostgresIntegrationTest() {

    private fun insertRequestedAttempt(): PaymentId {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into t_payment_attempt
                (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status,
                 provider_reference, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'AUTHORIZATION', 4500, 'KRW', 'REQUESTED', null, now(), now())
            """.trimIndent(),
            id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        )
        return PaymentId(id)
    }

    @Test
    fun `같은 시도에 승인이 겹쳐도 결과는 한 번만 적용된다`() {
        val paymentId = insertRequestedAttempt()
        gateway.rendezvousAuthorize(paymentId)
        val executor = Executors.newFixedThreadPool(2)

        val results: List<PaymentCommand.Authorize.Result> = try {
            List(2) { executor.submit<PaymentCommand.Authorize.Result> { paymentManagement.authorize(PaymentCommand.Authorize(paymentId)) } }
                .map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val stored = jdbcTemplate.queryForObject<String>(
            "select status from t_payment_attempt where id = ?", paymentId.value,
        )
        // 먼저 적용한 결말 하나가 남고, 진 쪽은 그것을 그대로 돌려받는다.
        assertEquals(1, results.map { it.status }.distinct().size, "두 호출이 서로 다른 결말을 돌려받았다: $results")
        assertEquals(stored, results.first().status.name)
        // 잠금은 DB 안의 이중 적용을 막는다. provider 쪽 이중 호출은 막지 못하고, 그것은 provider의
        // PaymentId 멱등성이 막는다. 두 번 불렸다는 사실을 고정해 둔다.
        assertEquals(2, gateway.authorizeCallsFor(paymentId))
    }
}
