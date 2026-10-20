package com.resilientpos.payment

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentReconciliationRequested
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.payment.internal.application.PaymentEventPublisher
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.payment.internal.domain.PaymentGateway
import com.resilientpos.platform.DistributedLock
import com.resilientpos.platform.Lock
import com.resilientpos.platform.Tx
import com.resilientpos.platform.TxRunner
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    init {
        // 프록시가 아닌 러너라 블록을 그대로 부른다. 저장소가 가짜라 트랜잭션이 필요 없다.
        Tx.initialize(TxRunner())
        // 잠금은 인스턴스 사이의 동시성만 다룬다. 단위 테스트의 판단에는 끼지 않는다.
        Lock.initialize(object : DistributedLock {
            override fun tryAcquire(key: String): String? = null
            override fun release(key: String, token: String) = Unit
        })
    }

    private val krw: Currency = Currency.getInstance("KRW")
    private val orderId: UUID = UUID.randomUUID()

    private class RecordingRepository(var existing: PaymentAttempt? = null) : PaymentAttemptRepository {
        /** 주면 `findByIdForUpdate`가 저장된 값 대신 이것을 돌려준다. 읽은 뒤 다른 호출이 먼저 확정한 경우를 만든다. */
        var lockedRead: PaymentAttempt? = null
        val saved = mutableListOf<PaymentAttempt>()
        val updated = mutableListOf<PaymentAttempt>()
        val stored = mutableMapOf<PaymentId, PaymentAttempt>()
        override fun findAuthorization(orderId: UUID, checkoutNo: Int) = existing
        override fun findLatestAuthorization(orderId: UUID, checkoutNo: Int) = stored.values.lastOrNull {
            it.orderId == orderId && it.checkoutNo == checkoutNo && it.kind == PaymentAttemptKind.AUTHORIZATION
        }
        override fun findById(paymentId: PaymentId) = stored[paymentId]
        override fun findUnknownAuthorizationIds(after: PaymentId?, limit: Int) = emptyList<PaymentId>()
        override fun countUnknownAuthorizations() = 0L
        override fun countReconciliationPendingAuthorizations() = 0L
        override fun findByIdForUpdate(paymentId: PaymentId) = lockedRead ?: stored[paymentId]
        override fun findByIdAndStoreIdForUpdate(paymentId: PaymentId, storeId: StoreId) =
            findByIdForUpdate(paymentId)?.takeIf { it.storeId == storeId }
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
    private class StubGateway(
        private val outcome: AuthorizationOutcome,
        private val queryOutcome: AuthorizationOutcome = outcome,
    ) : PaymentGateway {
        var calls = 0
        var queryCalls = 0
        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
            calls++
            return outcome
        }

        override fun query(request: AuthorizationRequest): AuthorizationOutcome {
            queryCalls++
            return queryOutcome
        }
    }

    /** 승인 결말. 금액은 시도 금액과 같아야 승인으로 굳는다. */
    private fun approved(amount: String = "4500") = AuthorizationOutcome.Approved("PROVIDER-REF", Money.of(amount, krw))

    /** `repository`를 주면 발행하는 순간까지 몇 번 갱신했는지도 적는다. 저장 → 발행 순서를 본다. */
    private class RecordingPublisher(private val repository: RecordingRepository? = null) : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        val updatedBeforePublish = mutableListOf<Int>()
        override fun publishEvent(event: Any) {
            published += event
            repository?.let { updatedBeforePublish += it.updated.size }
        }
    }

    private fun command(amount: String) = PaymentCommand.CreateAttempt(
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
    ): PaymentManagement {
        val ids = UuidV7IdentifierGenerator()
        val clock = Clock.fixed(Instant.parse("2026-09-22T01:00:00Z"), ZoneOffset.UTC)
        val events = PaymentEventPublisher(publisher)
        return PaymentManagement(
            repository,
            gateway,
            ids,
            clock,
            events,
            UNKNOWN_QUERY_LIMIT,
        )
    }

    @Test
    fun `승인이 필요한 시도는 생성 사실만 발행한다`() {
        val publisher = RecordingPublisher()

        val result = management(RecordingRepository(), publisher).createAttempt(command("4500"))

        val created = publisher.published.single() as PaymentEvent.AttemptCreated
        assertEquals(result.paymentId, created.paymentId)
        assertEquals(orderId, created.orderId)
        assertEquals(1, created.checkoutNo)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, created.kind)
        assertEquals(Money.of("4500", krw), created.amount)
        assertEquals(PaymentAttemptStatus.REQUESTED, result.status)
    }

    @Test
    fun `0원 시도는 생성과 승인 사실을 순서대로 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()

        val result = management(repository, publisher).createAttempt(command("0"))

        // 중간 상태(REQUESTED)를 저장하지 않고 최종 상태로 한 번만 넣는다.
        assertEquals(PaymentAttemptStatus.APPROVED, repository.saved.single().status)
        assertEquals(2, publisher.published.size)
        assertTrue(publisher.published[0] is PaymentEvent.AttemptCreated)
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
    fun `승인을 적용하면 저장한 뒤 승인 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher(repository)
        val gateway = StubGateway(approved())
        val management = management(repository, publisher, gateway)
        val created = management.createAttempt(command("4500"))
        publisher.published.clear()
        publisher.updatedBeforePublish.clear()

        val result = management.authorize(PaymentCommand.Authorize(created.paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
        val approved = publisher.published.single() as PaymentApproved
        assertEquals(created.paymentId.value, approved.paymentId)
        assertEquals("PROVIDER-REF", approved.providerReference)
        assertEquals(1, gateway.calls)
        assertEquals(listOf(1), publisher.updatedBeforePublish)
    }

    @Test
    fun `거절을 적용하면 거절 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Declined(null)))
        val created = management.createAttempt(command("4500"))
        publisher.published.clear()

        val result = management.authorize(PaymentCommand.Authorize(created.paymentId))

        assertEquals(PaymentAttemptStatus.DECLINED, result.status)
        val declined = publisher.published.single() as PaymentEvent.Declined
        assertEquals(created.paymentId, declined.paymentId)
    }

    @Test
    fun `미확인을 적용하면 미확인 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Unknown))
        val created = management.createAttempt(command("4500"))
        publisher.published.clear()

        val result = management.authorize(PaymentCommand.Authorize(created.paymentId))

        assertEquals(PaymentAttemptStatus.UNKNOWN, result.status)
        val unknown = publisher.published.single() as PaymentEvent.Unknown
        assertEquals(created.paymentId, unknown.paymentId)
        assertEquals(PaymentUnknownReason.RESULT_UNCONFIRMED, repository.stored.getValue(created.paymentId).unknownReason)
    }

    @Test
    fun `승인 응답의 금액이 시도와 다르면 승인으로 굳히지 않고 AMOUNT_MISMATCH 사유로 UNKNOWN을 적용한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(approved("4501")))
        val created = management.createAttempt(command("4500"))
        publisher.published.clear()

        val result = management.authorize(PaymentCommand.Authorize(created.paymentId))

        assertEquals(PaymentAttemptStatus.UNKNOWN, result.status)
        val stored = repository.stored.getValue(created.paymentId)
        assertEquals(PaymentUnknownReason.AMOUNT_MISMATCH, stored.unknownReason)
        assertEquals(null, stored.providerReference)
        assertEquals(created.paymentId, (publisher.published.single() as PaymentEvent.Unknown).paymentId)
    }

    @Test
    fun `승인 응답의 통화가 시도와 다르면 금액이 같아도 AMOUNT_MISMATCH 사유로 UNKNOWN을 적용한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val usdSameNumber = AuthorizationOutcome.Approved("PROVIDER-REF", Money.of("4502", Currency.getInstance("USD")))
        val management = management(repository, publisher, StubGateway(usdSameNumber))
        val created = management.createAttempt(command("4500"))

        val result = management.authorize(PaymentCommand.Authorize(created.paymentId))

        assertEquals(PaymentAttemptStatus.UNKNOWN, result.status)
        assertEquals(PaymentUnknownReason.AMOUNT_MISMATCH, repository.stored.getValue(created.paymentId).unknownReason)
    }

    @Test
    fun `결말이 난 시도는 provider를 부르지 않고 그 결말을 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(approved())
        val management = management(repository, publisher, gateway)
        // 0원 시도는 만들어질 때 이미 APPROVED다.
        val created = management.createAttempt(command("0"))
        publisher.published.clear()

        val result = management.authorize(PaymentCommand.Authorize(created.paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, result.status)
        assertEquals(0, gateway.calls)
        assertTrue(publisher.published.isEmpty())
        assertTrue(repository.updated.isEmpty())
    }

    @Test
    fun `없는 시도를 승인하려 하면 거절하고 provider를 부르지 않는다`() {
        val gateway = StubGateway(approved())
        val management = management(RecordingRepository(), RecordingPublisher(), gateway)

        val domain = assertFailsWith<DomainException> { management.authorize(PaymentCommand.Authorize(PaymentId(UUID.randomUUID()))) }
        assertEquals(PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND, domain.code)
        assertEquals(DomainErrorKind.NOT_FOUND, domain.kind)
        assertEquals(0, gateway.calls)
    }

    /** 거절된 시도 하나를 저장소에 둔다. */
    private fun declinedAttempt(repository: RecordingRepository, management: PaymentManagement): PaymentId {
        val created = management.createAttempt(command("4501"))
        management.authorize(PaymentCommand.Authorize(created.paymentId))
        assertEquals(PaymentAttemptStatus.DECLINED, repository.stored.getValue(created.paymentId).status)
        return created.paymentId
    }

    /** 그 시도가 속한 매장의 단말이 보내는 재결제 요청. */
    private fun retryCommand(repository: RecordingRepository, paymentId: PaymentId) =
        PaymentCommand.RetryAuthorization(paymentId, repository.stored.getValue(paymentId).storeId)

    @Test
    fun `다른 매장의 재결제는 없는 시도와 같아 거절하고 새 시도도 이벤트도 만들지 않는다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Declined(null)))
        val declined = declinedAttempt(repository, management)
        repository.saved.clear()
        publisher.published.clear()

        val failure = assertFailsWith<DomainException> {
            management.retryAuthorization(PaymentCommand.RetryAuthorization(declined, StoreId(UUID.randomUUID())))
        }

        assertEquals(PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND, failure.code)
        assertEquals(emptyList(), repository.saved)
        assertEquals(emptyList(), publisher.published)
    }

    @Test
    fun `최신 시도가 거절이면 같은 회차의 새 시도를 저장하고 생성 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Declined(null)))
        val declined = declinedAttempt(repository, management)
        repository.saved.clear()
        publisher.published.clear()

        val result = management.retryAuthorization(retryCommand(repository, declined))

        val retried = repository.saved.single()
        assertEquals(retried.id, result.paymentId)
        assertTrue(retried.id != declined)
        assertEquals(PaymentAttemptStatus.REQUESTED, result.status)
        assertEquals(1, retried.checkoutNo)
        val created = publisher.published.single() as PaymentEvent.AttemptCreated
        assertEquals(retried.id, created.paymentId)
    }

    @Test
    fun `이미 재결제한 거절 시도로 다시 요청하면 최신 시도를 돌려주고 아무것도 만들지 않는다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Declined(null)))
        val declined = declinedAttempt(repository, management)
        val first = management.retryAuthorization(retryCommand(repository, declined))
        repository.saved.clear()
        publisher.published.clear()

        val again = management.retryAuthorization(retryCommand(repository, declined))

        assertEquals(first.paymentId, again.paymentId)
        assertEquals(emptyList(), repository.saved)
        assertEquals(emptyList(), publisher.published)
    }

    @Test
    fun `최신 시도가 거절이 아니면 재결제를 거절하고 아무것도 만들지 않는다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Unknown))
        val created = management.createAttempt(command("4502"))
        management.authorize(PaymentCommand.Authorize(created.paymentId))
        repository.saved.clear()
        publisher.published.clear()

        val domain = assertFailsWith<DomainException> { management.retryAuthorization(retryCommand(repository, created.paymentId)) }
        assertEquals(PaymentAttempt.PAYMENT_NOT_RETRYABLE, domain.code)
        assertEquals(emptyList(), repository.saved)
        assertEquals(emptyList(), publisher.published)
    }

    @Test
    fun `취소 시도로 재결제하면 같은 회차에 승인 시도가 있어도 거절하고 아무것도 만들지 않는다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val management = management(repository, publisher, StubGateway(AuthorizationOutcome.Declined(null)))
        val declined = declinedAttempt(repository, management)
        val authorization = repository.stored.getValue(declined)
        val cancellation = PaymentAttempt.restore(
            id = PaymentId(UUID.randomUUID()),
            orderId = authorization.orderId,
            storeId = authorization.storeId,
            terminalId = authorization.terminalId,
            checkoutNo = authorization.checkoutNo,
            kind = PaymentAttemptKind.CANCELLATION,
            amount = authorization.amount,
            status = PaymentAttemptStatus.DECLINED,
            providerReference = null,
            retriedFrom = null,
            unknownReason = null,
            queryCount = 0,
            reconciliationRequestedAt = null,
        )
        repository.stored[cancellation.id] = cancellation
        repository.saved.clear()
        publisher.published.clear()

        val failure = assertFailsWith<DomainException> {
            management.retryAuthorization(PaymentCommand.RetryAuthorization(cancellation.id, cancellation.storeId))
        }

        assertEquals(PaymentAttempt.PAYMENT_NOT_RETRYABLE, failure.code)
        assertEquals(DomainErrorKind.UNPROCESSABLE, failure.kind)
        assertEquals(emptyList(), repository.saved)
        assertEquals(emptyList(), publisher.published)
    }

    @Test
    fun `없는 시도를 재결제하려 하면 거절한다`() {
        val management = management(RecordingRepository(), RecordingPublisher())

        val failure = assertFailsWith<DomainException> {
            management.retryAuthorization(PaymentCommand.RetryAuthorization(PaymentId(UUID.randomUUID()), StoreId(UUID.randomUUID())))
        }

        assertEquals(PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND, failure.code)
    }

    /** 승인 요청이 결과를 모른 채 끝난 시도 하나를 저장소에 둔다. 발행 기록은 비운다. */
    private fun unknownAttempt(management: PaymentManagement, publisher: RecordingPublisher, repository: RecordingRepository): PaymentId {
        val created = management.createAttempt(command("4502"))
        management.authorize(PaymentCommand.Authorize(created.paymentId))
        assertEquals(PaymentAttemptStatus.UNKNOWN, repository.stored.getValue(created.paymentId).status)
        publisher.published.clear()
        repository.updated.clear()
        return created.paymentId
    }

    @Test
    fun `조회가 승인이면 시도를 승인으로 확정하고 저장한 뒤 승인 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher(repository)
        val gateway = StubGateway(AuthorizationOutcome.Unknown, approved("4502"))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        publisher.updatedBeforePublish.clear()

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.APPROVED), result)
        val approved = publisher.published.single() as PaymentApproved
        assertEquals(unknown.value, approved.paymentId)
        assertEquals("PROVIDER-REF", approved.providerReference)
        assertEquals(listOf(1), publisher.updatedBeforePublish)
        assertEquals(1, gateway.queryCalls)
    }

    @Test
    fun `조회가 거절이면 시도를 거절로 확정하고 거절 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown, AuthorizationOutcome.Declined("PROVIDER-REF"))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.DECLINED), result)
        val declined = publisher.published.single() as PaymentEvent.Declined
        assertEquals(unknown, declined.paymentId)
    }

    @Test
    fun `조회로도 불명이면 상한 전에는 조회 횟수만 저장하고 발행하지 않고 UNKNOWN을 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown)
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.UNKNOWN), result)
        assertEquals(1, gateway.queryCalls)
        val saved = repository.updated.single()
        assertEquals(1, saved.queryCount)
        assertNull(saved.reconciliationRequestedAt)
        assertEquals(PaymentAttemptStatus.UNKNOWN, saved.status)
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `조회 횟수가 상한에 닿으면 넘긴 시각을 저장한 뒤 QUERY_LIMIT_EXCEEDED 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher(repository)
        val gateway = StubGateway(AuthorizationOutcome.Unknown)
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        publisher.updatedBeforePublish.clear()

        repeat(UNKNOWN_QUERY_LIMIT) { management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown)) }

        val stored = repository.stored.getValue(unknown)
        assertEquals(UNKNOWN_QUERY_LIMIT, stored.queryCount)
        assertEquals(Instant.parse("2026-09-22T01:00:00Z"), stored.reconciliationRequestedAt)
        val fact = publisher.published.single() as PaymentReconciliationRequested
        assertEquals(PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED, fact.reason)
        assertEquals(unknown.value, fact.paymentId)
        assertEquals(UNKNOWN_QUERY_LIMIT, fact.queryCount)
        assertEquals(PaymentUnknownReason.RESULT_UNCONFIRMED, fact.unknownReason)
        assertNull(fact.reportedAmount)
        assertNull(fact.reportedProviderReference)
        // 저장한 뒤 발행한다. 상한 전 두 번은 사실 없이 갱신만 했다.
        assertEquals(listOf(UNKNOWN_QUERY_LIMIT), publisher.updatedBeforePublish)
    }

    @Test
    fun `조회가 다른 금액의 승인이면 확정하지 않고 넘긴 뒤 알려 준 금액과 참조를 담은 AMOUNT_MISMATCH 사실을 발행한다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher(repository)
        val gateway = StubGateway(AuthorizationOutcome.Unknown, approved("4503"))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        publisher.updatedBeforePublish.clear()

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.UNKNOWN), result)
        val stored = repository.stored.getValue(unknown)
        assertEquals(1, stored.queryCount)
        assertEquals(Instant.parse("2026-09-22T01:00:00Z"), stored.reconciliationRequestedAt)
        assertNull(stored.providerReference)
        val fact = publisher.published.single() as PaymentReconciliationRequested
        assertEquals(PaymentReconciliationReason.AMOUNT_MISMATCH, fact.reason)
        assertEquals(Money.of("4503", krw), fact.reportedAmount)
        assertEquals("PROVIDER-REF", fact.reportedProviderReference)
        assertEquals(Money.of("4502", krw), fact.amount)
        assertEquals(listOf(1), publisher.updatedBeforePublish)
    }

    @Test
    fun `조회가 다른 통화의 승인이면 금액이 같아도 넘긴다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown, AuthorizationOutcome.Approved("PROVIDER-REF", Money.of("4502", Currency.getInstance("USD"))))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)

        management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        val fact = publisher.published.single() as PaymentReconciliationRequested
        assertEquals(PaymentReconciliationReason.AMOUNT_MISMATCH, fact.reason)
        assertEquals(Money.of("4502", Currency.getInstance("USD")), fact.reportedAmount)
    }

    @Test
    fun `넘긴 시도는 provider를 조회하지 않고 지금 상태를 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown, approved("4502"))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        repository.stored[unknown] = repository.stored.getValue(unknown)
            .requestReconciliationForAmountMismatch(Money.of("4503", krw), "REF", UuidV7IdentifierGenerator(), Instant.parse("2026-09-22T00:30:00Z")).aggregate

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.UNKNOWN), result)
        assertEquals(0, gateway.queryCalls)
        assertTrue(repository.updated.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `조회하는 동안 다른 호출이 먼저 넘겼으면 횟수도 쓰지 않고 발행하지 않는다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown, AuthorizationOutcome.Unknown)
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        // findById는 넘기지 않은 UNKNOWN을 읽었지만 잠그고 다시 읽으면 이미 넘겼다.
        repository.lockedRead = repository.stored.getValue(unknown)
            .requestReconciliationForAmountMismatch(Money.of("4503", krw), "REF", UuidV7IdentifierGenerator(), Instant.parse("2026-09-22T00:30:00Z")).aggregate

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.UNKNOWN), result)
        assertEquals(1, gateway.queryCalls)
        assertTrue(repository.updated.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `조회하는 동안 다른 호출이 먼저 넘겼으면 승인 조회 결과로도 덮지 않는다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown, approved("4502"))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        repository.lockedRead = repository.stored.getValue(unknown)
            .requestReconciliationForAmountMismatch(Money.of("4503", krw), "REF", UuidV7IdentifierGenerator(), Instant.parse("2026-09-22T00:30:00Z")).aggregate

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.UNKNOWN), result)
        assertTrue(repository.updated.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `UNKNOWN이 아닌 시도는 provider를 조회하지 않고 지금 상태를 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(approved())
        val management = management(repository, publisher, gateway)
        val approved = management.createAttempt(command("4500"))
        management.authorize(PaymentCommand.Authorize(approved.paymentId))
        publisher.published.clear()
        repository.updated.clear()

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(approved.paymentId))

        assertEquals(PaymentCommand.ResolveUnknown.Result(approved.paymentId, PaymentAttemptStatus.APPROVED), result)
        assertEquals(0, gateway.queryCalls)
        assertTrue(repository.updated.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `없는 시도를 확정하려 하면 거절하고 provider를 조회하지 않는다`() {
        val gateway = StubGateway(approved())
        val management = management(RecordingRepository(), RecordingPublisher(), gateway)

        val failure = assertFailsWith<DomainException> {
            management.resolveUnknown(PaymentCommand.ResolveUnknown(PaymentId(UUID.randomUUID())))
        }

        assertEquals(PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND, failure.code)
        assertEquals(0, gateway.queryCalls)
    }

    @Test
    fun `UNKNOWN이어도 취소 시도는 provider를 조회하지 않고 지금 상태를 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(approved())
        val management = management(repository, publisher, gateway)
        val cancellation = PaymentAttempt.restore(
            id = PaymentId(UUID.randomUUID()),
            orderId = orderId,
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            kind = PaymentAttemptKind.CANCELLATION,
            amount = Money.of("4500", krw),
            status = PaymentAttemptStatus.UNKNOWN,
            providerReference = null,
            retriedFrom = null,
            unknownReason = null,
            queryCount = 0,
            reconciliationRequestedAt = null,
        )
        repository.stored[cancellation.id] = cancellation

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(cancellation.id))

        assertEquals(PaymentCommand.ResolveUnknown.Result(cancellation.id, PaymentAttemptStatus.UNKNOWN), result)
        assertEquals(0, gateway.queryCalls)
        assertTrue(repository.updated.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `조회하는 동안 다른 호출이 먼저 확정했으면 덮지 않고 그 결말을 돌려준다`() {
        val repository = RecordingRepository()
        val publisher = RecordingPublisher()
        val gateway = StubGateway(AuthorizationOutcome.Unknown, AuthorizationOutcome.Declined("PROVIDER-REF"))
        val management = management(repository, publisher, gateway)
        val unknown = unknownAttempt(management, publisher, repository)
        // findById는 UNKNOWN을 읽었지만 잠그고 다시 읽으면 이미 APPROVED다.
        repository.lockedRead = repository.stored.getValue(unknown).confirmApproved("OTHER-REF", UuidV7IdentifierGenerator(), Instant.parse("2026-09-22T00:30:00Z")).aggregate

        val result = management.resolveUnknown(PaymentCommand.ResolveUnknown(unknown))

        assertEquals(PaymentCommand.ResolveUnknown.Result(unknown, PaymentAttemptStatus.APPROVED), result)
        assertEquals(1, gateway.queryCalls)
        assertTrue(repository.updated.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    private companion object {
        /** 테스트 설정(`resilientpos.payment.unknown-query-limit`)과 같다. */
        const val UNKNOWN_QUERY_LIMIT = 3
    }
}
