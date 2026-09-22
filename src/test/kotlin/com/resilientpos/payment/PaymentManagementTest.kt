package com.resilientpos.payment

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.context.ApplicationEventPublisher

/**
 * Facade가 **어떤 사실을 발행하는지** 고정한다.
 *
 * 행만 검사하는 통합 테스트로는 이것이 지켜지지 않는다. 발행 코드를 통째로 지워도 통과하기 때문이다.
 * 이벤트는 이 모듈의 공개 계약이라 소비자가 붙기 전에도 고정해 둬야 한다.
 * 저장소는 가짜를 쓴다. DB 없이 값 비교로 끝나는 검사다.
 */
class PaymentManagementTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val orderId: UUID = UUID.randomUUID()

    private class RecordingRepository(var inflight: PaymentAttempt? = null) : PaymentAttemptRepository {
        val saved = mutableListOf<PaymentAttempt>()
        override fun findInflightAuthorization(orderId: UUID, checkoutNo: Int) = inflight
        override fun save(attempt: PaymentAttempt) = attempt.also { saved += it }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) { published += event }
    }

    private fun command(amount: String) = CreatePaymentAttemptCommand(
        orderId = orderId,
        storeId = StoreId(UUID.randomUUID()),
        terminalId = TerminalId(UUID.randomUUID()),
        checkoutNo = 1,
        amount = Money.of(amount, krw),
    )

    private fun management(repository: PaymentAttemptRepository, publisher: ApplicationEventPublisher) =
        PaymentManagement(
            repository,
            UuidV7IdentifierGenerator(),
            Clock.fixed(Instant.parse("2026-09-22T01:00:00Z"), ZoneOffset.UTC),
            publisher,
        )

    @Test
    fun `승인이 필요한 시도는 생성 사실만 발행한다`() {
        val publisher = RecordingPublisher()

        val result = management(RecordingRepository(), publisher).createAttempt(command("4500"))

        val created = publisher.published.single() as PaymentAttemptCreated
        assertEquals(result.paymentId, created.paymentId)
        assertEquals(orderId, created.orderId)
        assertEquals(1, created.checkoutNo)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, created.kind)
        assertEquals(Money.of("4500", krw), created.amount)
        assertEquals(PaymentAttemptStatus.REQUESTED, result.status)
    }

    @Test
    fun `0원 시도는 생성과 승인 사실을 순서대로 발행한다`() {
        val publisher = RecordingPublisher()

        val result = management(RecordingRepository(), publisher).createAttempt(command("0"))

        assertEquals(2, publisher.published.size)
        assertTrue(publisher.published[0] is PaymentAttemptCreated)
        val approved = publisher.published[1] as PaymentApproved
        assertEquals(result.paymentId.value, approved.paymentId)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, approved.kind)
        assertEquals(null, approved.providerReference)
        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
    }

    @Test
    fun `이미 진행 중인 승인이 있으면 저장도 발행도 하지 않는다`() {
        // 다시 발행하면 소비자가 같은 사실을 두 번 본다. 이 시도는 새로 일어난 일이 아니다.
        val existing = PaymentAttempt.create(command("4500"), UuidV7IdentifierGenerator(), Instant.parse("2026-09-22T00:00:00Z")).aggregate
        val repository = RecordingRepository(inflight = existing)
        val publisher = RecordingPublisher()

        val result = management(repository, publisher).createAttempt(command("4500"))

        assertEquals(existing.id, result.paymentId)
        assertEquals(emptyList(), publisher.published)
        assertEquals(emptyList(), repository.saved)
    }
}
