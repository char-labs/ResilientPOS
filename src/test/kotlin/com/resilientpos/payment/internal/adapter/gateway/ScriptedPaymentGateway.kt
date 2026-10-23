package com.resilientpos.payment.internal.adapter.gateway

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import jakarta.persistence.EntityManagerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 통합 테스트의 결제 gateway. 앱의 [FakePaymentGateway]에 위임하고, 테스트는 자기 금액이나 paymentId에만 다른 동작을 건다.
 * 통합 테스트는 컨텍스트를 같이 써서 앞 테스트가 남긴 메시지도 이 gateway를 부른다. 동작을 키에 묶으면 그 호출은 기본 동작을 탄다.
 *
 * 시도별 승인·조회 호출 수, 조회 순서, 호출 순간 이 스레드에 DB 자원(EntityManager·커넥션)이 묶여 있었는지는 늘 적는다.
 * 트랜잭션 여부만 보면 빈 트랜잭션 스코프에 묶인 EntityManager를 놓친다.
 * 건 동작과 기록은 테스트가 끝날 때마다 지운다(`IntegrationTestIsolationListener`). 동작은 순서에 기대는 상태를 두지 않아,
 * 같은 금액을 쓰는 다른 클래스의 늦은 호출이 먼저 와도 이 테스트의 결과가 바뀌지 않는다.
 */
class ScriptedPaymentGateway(
    private val delegate: FakePaymentGateway,
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
) : PaymentGateway {

    private val authorizeScripts = ConcurrentHashMap<Any, (AuthorizationRequest) -> AuthorizationOutcome>()
    private val queryScripts = ConcurrentHashMap<Any, (AuthorizationRequest) -> AuthorizationOutcome>()
    private val authorizeCalls = ConcurrentHashMap<PaymentId, AtomicInteger>()
    private val queryCalls = ConcurrentHashMap<PaymentId, AtomicInteger>()
    private val resourceBoundDuringAuthorize = ConcurrentHashMap<PaymentId, Boolean>()
    private val resourceBoundDuringQuery = ConcurrentHashMap<PaymentId, Boolean>()
    private val queried = CopyOnWriteArrayList<PaymentId>()

    override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
        authorizeCalls.computeIfAbsent(request.paymentId) { AtomicInteger() }.incrementAndGet()
        resourceBoundDuringAuthorize[request.paymentId] = resourceBound()
        val script = authorizeScripts[request.paymentId] ?: authorizeScripts[request.amount]
        return script?.invoke(request) ?: delegate.authorize(request)
    }

    override fun query(request: AuthorizationRequest): AuthorizationOutcome {
        queryCalls.computeIfAbsent(request.paymentId) { AtomicInteger() }.incrementAndGet()
        resourceBoundDuringQuery[request.paymentId] = resourceBound()
        queried += request.paymentId
        val script = queryScripts[request.paymentId] ?: queryScripts[request.amount]
        return script?.invoke(request) ?: delegate.query(request)
    }

    /** 이 금액의 승인에서 던진다. 어댑터가 포트 계약(던지지 않는다)을 어긴 경우다. */
    fun throwOnAuthorize(amount: Money) {
        authorizeScripts[amount] = { error("어댑터가 던졌다") }
    }

    /**
     * 이 금액의 승인 가운데 [first]가 아닌 시도(재결제)를 승인한다. 기본 동작은 같은 금액의 재결제를 첫 시도와 같은 결말로 만든다.
     * 첫 시도가 자리 잡은 뒤에 건다. "그 금액의 첫 호출"처럼 순서에 기대는 상태를 두지 않아서, 다른 클래스의 늦은 호출이 먼저 와도 결과가 같다.
     */
    fun approveRetriesOf(first: PaymentId, amount: Money) {
        authorizeScripts[amount] = { request ->
            if (request.paymentId == first) {
                delegate.authorize(request)
            } else {
                AuthorizationOutcome.Approved("APPROVED-${request.paymentId.value}", request.amount)
            }
        }
    }

    /** 이 시도의 조회에서 던진다. 확정 트랜잭션의 DB 오류 같은 한 건짜리 실패를 흉내 낸다. */
    fun failQuery(paymentId: PaymentId) {
        queryScripts[paymentId] = { error("조회 실패를 흉내 낸다") }
    }

    /** 이 시도의 승인 두 번이 모두 도착할 때까지 붙잡아 둔다. 먼저 온 쪽은 승인, 나중 쪽은 거절을 받는다. */
    fun rendezvousAuthorize(paymentId: PaymentId) {
        authorizeScripts[paymentId] = rendezvous("두 호출이 provider에서 만나지 못했다")
    }

    /** 이 시도의 조회 두 번이 모두 도착할 때까지 붙잡아 둔다. 먼저 온 쪽은 승인, 나중 쪽은 거절을 받는다. */
    fun rendezvousQuery(paymentId: PaymentId) {
        queryScripts[paymentId] = rendezvous("두 조회가 provider에서 만나지 못했다")
    }

    fun authorizeCallsFor(paymentId: PaymentId): Int = authorizeCalls[paymentId]?.get() ?: 0

    fun queryCallsFor(paymentId: PaymentId): Int = queryCalls[paymentId]?.get() ?: 0

    fun resourceBoundDuringAuthorize(paymentId: PaymentId): Boolean? = resourceBoundDuringAuthorize[paymentId]

    fun resourceBoundDuringQuery(paymentId: PaymentId): Boolean? = resourceBoundDuringQuery[paymentId]

    /** 조회한 시도를 조회한 순서대로 돌려준다. 다른 테스트의 시도도 섞일 수 있어 단언은 자기 시도로 거른다. */
    fun queried(): List<PaymentId> = queried.toList()

    fun reset() {
        authorizeScripts.clear()
        queryScripts.clear()
        authorizeCalls.clear()
        queryCalls.clear()
        resourceBoundDuringAuthorize.clear()
        resourceBoundDuringQuery.clear()
        queried.clear()
    }

    private fun resourceBound(): Boolean =
        TransactionSynchronizationManager.hasResource(entityManagerFactory) || TransactionSynchronizationManager.hasResource(dataSource)

    private fun rendezvous(timeoutMessage: String): (AuthorizationRequest) -> AuthorizationOutcome {
        val arrived = CountDownLatch(2)
        val position = AtomicInteger()
        return { request ->
            val mine = position.getAndIncrement()
            arrived.countDown()
            check(arrived.await(5, TimeUnit.SECONDS)) { timeoutMessage }
            if (mine == 0) AuthorizationOutcome.Approved("FIRST", request.amount) else AuthorizationOutcome.Declined("SECOND")
        }
    }
}
