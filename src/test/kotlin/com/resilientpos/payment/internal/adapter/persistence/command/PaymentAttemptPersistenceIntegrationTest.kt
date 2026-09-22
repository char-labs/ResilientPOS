package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired

/**
 * 한 주문에 결제 시도가 여러 건 달리는지 실제 PostgreSQL에서 확인한다.
 * 복합 자연 키를 두지 않기로 한 결정이 테이블에서도 성립하는지가 이 테스트의 요지다.
 */
class PaymentAttemptPersistenceIntegrationTest(
    @Autowired private val repository: PaymentAttemptJpaRepository,
) : PostgresIntegrationTest() {

    private val krw: Currency = Currency.getInstance("KRW")
    private val ids = UuidV7IdentifierGenerator()
    private val now: Instant = Instant.parse("2026-09-22T01:00:00Z")

    private fun attempt(orderId: UUID, amount: String = "4500", checkoutNo: Int = 1) =
        PaymentAttempt.create(
            CreatePaymentAttemptCommand(
                orderId = orderId,
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = checkoutNo,
                amount = Money.of(amount, krw),
            ),
            ids,
            now,
        ).aggregate

    @Test
    fun `한 주문에 결제 시도가 여러 건 저장된다`() {
        val orderId = UUID.randomUUID()

        repository.saveAll(
            listOf(
                PaymentAttemptEntity.from(attempt(orderId)),
                PaymentAttemptEntity.from(attempt(orderId)),
                PaymentAttemptEntity.from(attempt(orderId, checkoutNo = 2)),
            ),
        )
        repository.flush()

        assertEquals(3, repository.findAllByOrderId(orderId).size)
    }

    @Test
    fun `저장한 시도를 도메인으로 그대로 복원한다`() {
        val orderId = UUID.randomUUID()
        val saved = attempt(orderId, amount = "12345.0000".substringBefore("."))

        repository.saveAll(listOf(PaymentAttemptEntity.from(saved)))
        repository.flush()

        val restored = repository.findAllByOrderId(orderId).single().toDomain()
        assertEquals(saved.id, restored.id)
        assertEquals(saved.orderId, restored.orderId)
        assertEquals(saved.storeId, restored.storeId)
        assertEquals(saved.terminalId, restored.terminalId)
        assertEquals(saved.checkoutNo, restored.checkoutNo)
        assertEquals(saved.amount, restored.amount)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, restored.kind)
        assertEquals(PaymentAttemptStatus.REQUESTED, restored.status)
        assertNull(restored.providerReference)
    }

    @Test
    fun `0원 시도도 승인 상태 그대로 저장되고 복원된다`() {
        val orderId = UUID.randomUUID()

        repository.saveAll(listOf(PaymentAttemptEntity.from(attempt(orderId, amount = "0"))))
        repository.flush()

        val restored = repository.findAllByOrderId(orderId).single().toDomain()
        assertEquals(PaymentAttemptStatus.APPROVED, restored.status)
        assertEquals(Money.of("0", krw), restored.amount)
        assertNull(restored.providerReference)
    }
}
