package com.resilientpos.payment

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.payment.internal.domain.PaymentGateway
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations

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

    private class RecordingRepository(var existing: PaymentAttempt? = null) : PaymentAttemptRepository {
        val saved = mutableListOf<PaymentAttempt>()
        val updated = mutableListOf<PaymentAttempt>()
        val stored = mutableMapOf<PaymentId, PaymentAttempt>()
        override fun findAuthorization(orderId: UUID, checkoutNo: Int) = existing
        override fun findById(paymentId: PaymentId) = stored[paymentId]
        override fun findByIdForUpdate(paymentId: PaymentId) = stored[paymentId]
        override fun update(attempt: PaymentAttempt) = attempt.also {
            updated += it
            stored[it.id] = it
        }
        override fun save(attempt: PaymentAttempt) = attempt.also {
            saved += it
            stored[it.id] = it
        }
    }

    /** 정해진 결말만 돌려주고 불린 횟수를 센다. */
    private class StubGateway(private val outcome: AuthorizationOutcome) : PaymentGateway {
        var calls = 0
        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
            calls++
            return outcome
        }
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

    private fun management(
        repository: PaymentAttemptRepository,
        publisher: ApplicationEventPublisher,
        gateway: PaymentGateway = StubGateway(AuthorizationOutcome.Unknown),
    ) = PaymentManagement(
        repository,
        gateway,
        UuidV7IdentifierGenerator(),
        Clock.fixed(Instant.parse("2026-09-22T01:00:00Z"), ZoneOffset.UTC),
        publisher,
        TransactionOperations.withoutTransaction(),
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
    fun `그 checkout에 승인 시도가 이미 있으면 저장도 발행도 하지 않는다`() {
        // 다시 발행하면 소비자가 같은 사실을 두 번 본다. 이 시도는 새로 일어난 일이 아니다.
        val existing = PaymentAttempt.create(command("4500"), UuidV7IdentifierGenerator(), Instant.parse("2026-09-22T00:00:00Z")).aggregate
        val repository = RecordingRepository(existing = existing)
        val publisher = RecordingPublisher()

        val result = management(repository, publisher).createAttempt(command("4500"))

        assertEquals(existing.id, result.paymentId)
        assertEquals(emptyList(), publisher.published)
        assertEquals(emptyList(), repository.saved)
    }

    @Test
    fun `승인을 적용하면 승인 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Approved("PROVIDER-REF"))
        val management = management(repository, publisher, gateway)
        val created = management.createAttempt(command("4500"))
        publisher.published.clear()

        val result = management.authorize(AuthorizePaymentCommand(created.paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
        val approved = publisher.published.single() as PaymentApproved
        assertEquals(created.paymentId.value, approved.paymentId)
        assertEquals("PROVIDER-REF", approved.providerReference)
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `거절을 적용하면 거절 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Declined(null)))
        val created = management.createAttempt(command("4500"))
        publisher.published.clear()

        val result = management.authorize(AuthorizePaymentCommand(created.paymentId))

        assertEquals(PaymentAttemptStatus.DECLINED, result.status)
        val declined = publisher.published.single() as PaymentDeclined
        assertEquals(created.paymentId, declined.paymentId)
    }

    @Test
    fun `결말이 난 시도는 provider를 부르지 않고 그 결말을 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Approved("PROVIDER-REF"))
        val management = management(repository, publisher, gateway)
        // 0원 시도는 만들어질 때 이미 APPROVED다.
        val created = management.createAttempt(command("0"))
        publisher.published.clear()

        val result = management.authorize(AuthorizePaymentCommand(created.paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
        assertEquals(0, gateway.calls)
        assertTrue(publisher.published.isEmpty())
        assertTrue(repository.updated.isEmpty())
    }

    @Test
    fun `없는 시도를 승인하려 하면 거절하고 provider를 부르지 않는다`() {
        val gateway = StubGateway(AuthorizationOutcome.Approved("PROVIDER-REF"))
        val management = management(RecordingRepository(), RecordingPublisher(), gateway)

        val thrown = runCatching { management.authorize(AuthorizePaymentCommand(PaymentId(UUID.randomUUID()))) }
            .exceptionOrNull()

        val domain = assertIs<DomainException>(thrown)
        assertEquals(PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND, domain.code)
        assertEquals(DomainErrorKind.NOT_FOUND, domain.kind)
        assertEquals(0, gateway.calls)
    }
}
