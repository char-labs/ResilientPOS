package com.resilientpos.payment

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentReconciliationRequested
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Transition
import com.resilientpos.payment.internal.application.PaymentEventPublisher
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.payment.internal.domain.PaymentGateway
import com.resilientpos.platform.Lock
import com.resilientpos.platform.Tx
import com.resilientpos.platform.logger
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * `payment` 모듈의 유일한 진입점. 결제 정본 상태를 바꾸는 일은 전부 여기를 거친다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 구현이 둘이 될 때 뽑는다.
 */
@Service
class PaymentManagement(
    private val repository: PaymentAttemptRepository,
    private val gateway: PaymentGateway,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: PaymentEventPublisher,
    @Value("\${resilientpos.payment.unknown-query-limit}") private val unknownQueryLimit: Int,
) {

    /**
     * 한 checkout의 승인 시도를 만든다. 같은 요청이 다시 와도 시도를 두 번 만들지 않는다.
     *
     * 그 checkout에 승인 시도가 **결말과 무관하게** 이미 있으면 그것을 돌려주고 이벤트를 다시 내지 않는다.
     * `OrderPaymentRequested`는 checkout 회차마다 한 번 나므로 같은 `(orderId, checkoutNo)`가 다시 오면
     * 재시도가 아니라 같은 사실의 재배달이다. 진행 중인 시도만 찾으면 거절된 시도를 못 보고 새 `PaymentId`로
     * provider를 다시 부르는데, 멱등 키가 달라 provider도 막아 주지 못한다. 거절 뒤 재결제는 사용자가
     * 명시적으로 요청하는 별도 명령이 할 일이다 — 사실의 재배달과 재결제 의도를 같은 입구로 받지 않는다.
     *
     * 같은 사실이 여러 인스턴스에 동시에 오면 [Lock]이 줄 세운다. 늦게 온 요청은 앞 요청의
     * 커밋을 기다렸다가 그 시도를 돌려받는다. 잠금은 최선 노력이고 방어선은 DB다. 사실로 만든 승인 시도는 checkout
     * 회차당 하나라서(`retriedFrom`이 없는 행의 unique) 잠금이 통하지 않아도 두 번째가 들어가지 않는다.
     * 그때 늦은 요청은 커밋에서 실패한다(`persist`가 flush를 미룬다).
     */
    fun createAttempt(command: PaymentCommand.CreateAttempt): PaymentCommand.CreateAttempt.Result =
        Lock.executeWithLock(checkoutLockKey(command.orderId, command.checkoutNo)) {
            val existing = repository.findAuthorization(command.orderId, command.checkoutNo)
            if (existing != null) {
                return@executeWithLock PaymentCommand.CreateAttempt.Result(existing.id, existing.status)
            }

            val now = clock.instant()
            val created = PaymentAttempt.create(command, identifierGenerator, now)
            val freeApproval = created.aggregate.approveIfFree(identifierGenerator, now)
            val saved = repository.save(freeApproval?.aggregate ?: created.aggregate)
            created.events.forEach(eventPublisher::publish)
            freeApproval?.events?.forEach(eventPublisher::publish)
            PaymentCommand.CreateAttempt.Result(saved.id, saved.status)
        }

    /**
     * 기록된 시도에 provider 승인을 요청하고 결과를 적용한다.
     *
     * provider 호출은 트랜잭션 밖이다(도메인 모델 §4.6). `NOT_SUPPORTED`로 호출자 트랜잭션이 있으면 멈춘다.
     * 합류하면 적용의 잠금 조회가 먼저 읽어 둔 낡은 값을 돌려받는다. 적용은 `Tx.masterTx`가 새 트랜잭션으로 열어 반환 전에 커밋한다.
     *
     * 결말이 난 시도면 provider를 부르지 않는다(0원·재배달). 이중 청구 방어는 provider의 `PaymentId` 멱등성이다.
     * 0원 시도는 [createAttempt]가 만들자마자 승인해 저장하므로 `REQUESTED`로 여기 오지 않는다.
     *
     * 호출자 트랜잭션에 합류하지 않으므로 §4.6 sync intake처럼 한 커밋으로 묶어야 하는 흐름 안에서 부르지 않는다.
     * 그 안에서 부르면 바깥이 롤백돼도 적용 결과는 따로 커밋된다.
     *
     * 처음 읽는 `findById`는 짧은 트랜잭션(`Tx.masterTx`)으로 감싼다. 그 트랜잭션이 끝나며 EntityManager를 놓으므로
     * provider를 부르는 동안 DB 자원을 쥐지 않는다. `findById`는 `SUPPORTS`라 그냥 부르면 트랜잭션을 열지 않고
     * `NOT_SUPPORTED` 구간이 끝날 때까지 EntityManager가 묶인다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun authorize(command: PaymentCommand.Authorize): PaymentCommand.Authorize.Result {
        val attempt = Tx.masterTx { repository.findById(command.paymentId) } ?: throw PaymentAttempt.notFound()
        if (attempt.status != PaymentAttemptStatus.REQUESTED) {
            return PaymentCommand.Authorize.Result(attempt.id, attempt.status)
        }

        val outcome = gateway.authorize(AuthorizationRequest(attempt.id, attempt.amount))

        return Tx.masterTx { applyOutcome(command.paymentId, outcome) }
    }

    /**
     * 거절된 승인 시도를 같은 checkout 회차에서 다시 시도한다. 새 시도를 만들 뿐 provider는 부르지 않는다 —
     * 생성 사실을 받는 리스너가 [authorize]로 이어받는다.
     *
     * 대상이 그 회차의 최신 승인 시도가 아니면 이미 다시 시도한 것이라 최신 시도를 돌려준다. 응답을 잃고 다시 보낸
     * 요청이 새 provider 호출이 되지 않게 하려는 것이다. 최신인데 거절이 아니면 aggregate가 거절한다.
     *
     * 대상 시도는 인증된 단말의 매장 조건으로 잠근다. 매장이 다른 시도는 없는 시도와 같다.
     * 대상 행을 잠그고 최신을 읽는다. 겹친 두 요청 중 진 쪽은 앞 커밋을 기다렸다가 그 새 시도를 최신으로 본다.
     * 트랜잭션을 `Tx.masterTx`로 열어 반환 뒤의 로그가 커밋 뒤의 사실이 되게 한다([authorize]와 같다).
     * 같은 이유로 sync intake의 한 커밋 안에서 부르지 않는다. 바깥이 롤백돼도 새 시도와 생성 사실이 따로 커밋된다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun retryAuthorization(command: PaymentCommand.RetryAuthorization): PaymentCommand.RetryAuthorization.Result {
        val (result, retried) = Tx.masterTx { retry(command) }
        if (retried) {
            log.info { "결제를 다시 시도한다 declinedPaymentId=${command.paymentId.value} paymentId=${result.paymentId.value}" }
        }
        return result
    }

    /**
     * 결과를 모른 채 남은 시도를 provider 조회로 확정한다. 확정은 승인 요청에서 바로 결말이 났을 때와 같은 행 상태와 같은 사실을 낸다.
     *
     * 조회는 트랜잭션 밖이다([authorize]와 같다). `NOT_SUPPORTED`로 호출자 트랜잭션이 있으면 멈추고, 처음 읽는 `findById`와
     * 확정은 `Tx.masterTx`가 따로 열어 반환 전에 커밋한다. 호출자 트랜잭션에 합류하지 않으므로 §4.6 sync intake의 한 커밋 안에서 부르지 않는다.
     * 그 안에서 부르면 바깥이 롤백돼도 확정 결과는 따로 커밋된다.
     *
     * `UNKNOWN`이 아니거나 승인 시도가 아니거나 reconciliation으로 넘긴 시도면 provider를 부르지 않고 지금 상태를 돌려준다.
     * 조회 요청은 승인 요청이라 취소 시도에 쓰면 취소를 승인 조회로 확정하게 된다. 넘긴 시도는 reconciliation의 결론으로만 확정된다.
     *
     * 조회도 불명이면 가짜 성공도 가짜 실패도 만들지 않고 조회 횟수만 하나 올린다. 횟수가 상한(`unknown-query-limit`)에 닿으면
     * 시도를 넘기고 [PaymentReconciliationRequested]를 같은 커밋에 발행한다. 조회가 승인인데 금액(통화 포함)이 시도와 다르면 확정하지 않고 바로 넘긴다.
     * 금액이 같은 승인과 거절은 확정한다.
     *
     * 정합성은 행을 잠근 뒤 `UNKNOWN`이고 넘기지 않았음을 다시 확인하는 데서 나온다. 조회하는 동안 다른 호출이 먼저 확정하거나 넘겼으면 덮지 않고 지금 상태를 돌려준다.
     * 넘김 WARN은 트랜잭션이 반환된(커밋된) 뒤에 남긴다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun resolveUnknown(command: PaymentCommand.ResolveUnknown): PaymentCommand.ResolveUnknown.Result {
        val attempt = Tx.masterTx { repository.findById(command.paymentId) } ?: throw PaymentAttempt.notFound()
        if (attempt.status != PaymentAttemptStatus.UNKNOWN || attempt.kind != PaymentAttemptKind.AUTHORIZATION || attempt.reconciliationRequestedAt != null) {
            return PaymentCommand.ResolveUnknown.Result(attempt.id, attempt.status)
        }

        val outcome = gateway.query(AuthorizationRequest(attempt.id, attempt.amount))
        return when (outcome) {
            is AuthorizationOutcome.Declined -> confirmUnknown(command.paymentId, outcome)
            is AuthorizationOutcome.Approved ->
                if (outcome.amount == attempt.amount) {
                    confirmUnknown(command.paymentId, outcome)
                } else {
                    recordAndLog(command.paymentId) { current, now ->
                        current.requestReconciliationForAmountMismatch(outcome.amount, outcome.providerReference, identifierGenerator, now)
                    }
                }
            AuthorizationOutcome.Unknown ->
                recordAndLog(command.paymentId, logUnconfirmed = true) { current, now ->
                    current.recordUnknownQuery(unknownQueryLimit, identifierGenerator, now)
                }
        }
    }

    private fun confirmUnknown(paymentId: PaymentId, outcome: AuthorizationOutcome): PaymentCommand.ResolveUnknown.Result {
        val (result, confirmed) = Tx.masterTx { confirm(paymentId, outcome) }
        if (confirmed != null) {
            log.info {
                "결과 불명 결제를 확정했다 paymentId=${confirmed.id.value} orderId=${confirmed.orderId} " +
                    "checkoutNo=${confirmed.checkoutNo} status=${confirmed.status}"
            }
        }
        return result
    }

    /** 기록·넘김을 한 트랜잭션으로 저장하고, 반환된(커밋된) 뒤에 WARN을 남긴다. 아무것도 쓰지 않았으면 남기지 않는다. */
    private fun recordAndLog(
        paymentId: PaymentId,
        logUnconfirmed: Boolean = false,
        transition: (PaymentAttempt, Instant) -> Transition<PaymentAttempt, PaymentReconciliationRequested>,
    ): PaymentCommand.ResolveUnknown.Result {
        val (result, recorded) = Tx.masterTx { lockAndRecord(paymentId, transition) }
        if (recorded == null) return result

        val attempt = recorded.aggregate
        if (logUnconfirmed) {
            log.warn { "조회로도 결과를 확인하지 못했다 paymentId=${attempt.id.value} orderId=${attempt.orderId} queryCount=${attempt.queryCount}" }
        }
        recorded.events.forEach {
            log.warn {
                "결제 시도를 reconciliation으로 넘겼다 paymentId=${it.paymentId} orderId=${it.orderId} reason=${it.reason} " +
                    "queryCount=${it.queryCount} reportedAmount=${it.reportedAmount} reportedProviderReference=${it.reportedProviderReference}"
            }
        }
        return result
    }

    /**
     * 잠그고 다시 읽는다. 아직 넘기지 않은 `UNKNOWN`이면 [transition]이 정한 기록·넘김을 저장하고 발행한 뒤 그 전이를 함께 돌려준다.
     * 이미 다른 호출이 확정하거나 넘겼으면 아무것도 쓰지 않고 `null`과 지금 상태를 돌려준다.
     */
    private fun lockAndRecord(
        paymentId: PaymentId,
        transition: (PaymentAttempt, Instant) -> Transition<PaymentAttempt, PaymentReconciliationRequested>,
    ): Pair<PaymentCommand.ResolveUnknown.Result, Transition<PaymentAttempt, PaymentReconciliationRequested>?> {
        val current = repository.findByIdForUpdate(paymentId) ?: throw PaymentAttempt.notFound()
        if (current.status != PaymentAttemptStatus.UNKNOWN || current.reconciliationRequestedAt != null) {
            return PaymentCommand.ResolveUnknown.Result(current.id, current.status) to null
        }

        val recorded = transition(current, clock.instant())
        updateAndPublish(recorded, eventPublisher::publish)
        return PaymentCommand.ResolveUnknown.Result(current.id, recorded.aggregate.status) to recorded
    }

    /** 잠그고 다시 읽는다. 호출하는 동안 다른 `authorize`가 먼저 적용했으면 덮지 않고 그 결말을 돌려준다. */
    private fun applyOutcome(paymentId: PaymentId, outcome: AuthorizationOutcome): PaymentCommand.Authorize.Result {
        val current = repository.findByIdForUpdate(paymentId) ?: throw PaymentAttempt.notFound()
        if (current.status != PaymentAttemptStatus.REQUESTED) {
            return PaymentCommand.Authorize.Result(current.id, current.status)
        }

        val now = clock.instant()
        val applied = when (outcome) {
            is AuthorizationOutcome.Approved ->
                if (outcome.amount == current.amount) {
                    updateAndPublish(current.approve(outcome.providerReference, identifierGenerator, now), eventPublisher::publish)
                } else {
                    // provider가 분명히 승인했어도 다른 금액은 믿지 않고 결과 불명으로 둔다. 굳히면 주문의 markPaid가 거절해 DLT로 간다.
                    log.warn {
                        "승인 응답의 금액이 시도와 다르다 paymentId=${current.id.value} expected=${current.amount} " +
                            "reported=${outcome.amount} providerReference=${outcome.providerReference}"
                    }
                    updateAndPublish(
                        current.markUnknown(PaymentUnknownReason.AMOUNT_MISMATCH, identifierGenerator, now),
                        eventPublisher::publish,
                    )
                }
            is AuthorizationOutcome.Declined ->
                updateAndPublish(current.decline(outcome.providerReference, identifierGenerator, now), eventPublisher::publish)
            AuthorizationOutcome.Unknown ->
                updateAndPublish(current.markUnknown(PaymentUnknownReason.RESULT_UNCONFIRMED, identifierGenerator, now), eventPublisher::publish)
        }
        return PaymentCommand.Authorize.Result(applied.id, applied.status)
    }

    /**
     * 잠그고 다시 읽는다. 아직 넘기지 않은 `UNKNOWN`이면 조회 결과대로 확정하고 그 시도를 함께 돌려준다. 이미 다른 호출이 확정하거나 넘겼으면 `null`과 지금 상태를 돌려준다.
     * 불명 결과와 다른 금액의 승인은 호출 전에 걸러지므로 여기 오지 않는다.
     */
    private fun confirm(
        paymentId: PaymentId,
        outcome: AuthorizationOutcome,
    ): Pair<PaymentCommand.ResolveUnknown.Result, PaymentAttempt?> {
        val current = repository.findByIdForUpdate(paymentId) ?: throw PaymentAttempt.notFound()
        if (current.status != PaymentAttemptStatus.UNKNOWN || current.reconciliationRequestedAt != null) {
            return PaymentCommand.ResolveUnknown.Result(current.id, current.status) to null
        }

        val now = clock.instant()
        val confirmed = when (outcome) {
            is AuthorizationOutcome.Approved ->
                updateAndPublish(current.confirmApproved(outcome.providerReference, identifierGenerator, now), eventPublisher::publish)
            is AuthorizationOutcome.Declined ->
                updateAndPublish(current.confirmDeclined(outcome.providerReference, identifierGenerator, now), eventPublisher::publish)
            AuthorizationOutcome.Unknown -> error("불명 결과는 확정하지 않는다")
        }
        return PaymentCommand.ResolveUnknown.Result(confirmed.id, confirmed.status) to confirmed
    }

    /**
     * 거절된 시도의 행을 매장 조건으로 잠그고 그 회차의 최신 승인 시도를 읽는다. 최신이 아니면 이미 다시 시도한 것이라 최신 시도를 돌려준다.
     * 새 시도를 만들었으면 `true`를 함께 돌려준다.
     */
    private fun retry(command: PaymentCommand.RetryAuthorization): Pair<PaymentCommand.RetryAuthorization.Result, Boolean> {
        val target = repository.findByIdAndStoreIdForUpdate(command.paymentId, command.storeId)
        if (target == null) {
            // 운영자가 구분할 수 있게 매장 불일치만 WARN으로 남긴다. 잠그지 않는 존재 확인이다. 토큰은 남기지 않는다.
            if (repository.findById(command.paymentId) != null) {
                log.warn {
                    "다른 매장의 결제 시도 재결제 요청을 시도 없음으로 처리한다 paymentId=${command.paymentId.value} " +
                        "storeId=${command.storeId.value}"
                }
            }
            throw PaymentAttempt.notFound()
        }
        // 이미 다시 시도했는지는 승인 시도에만 뜻이 있다. 취소 시도는 aggregate의 `retry()`가 거절한다.
        if (target.kind == PaymentAttemptKind.AUTHORIZATION) {
            val latest = repository.findLatestAuthorization(target.orderId, target.checkoutNo)
            if (latest != null && latest.id != target.id) {
                return PaymentCommand.RetryAuthorization.Result(latest.id, latest.status) to false
            }
        }

        val transition = target.retry(identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publish)
        return PaymentCommand.RetryAuthorization.Result(saved.id, saved.status) to true
    }

    /**
     * 결말을 저장한 뒤 그 사실을 발행한다. 발행 기록이 결과와 같은 커밋에 들어가야 하므로 이 트랜잭션 안에서 발행한다(도메인 모델 §4.3).
     * `publish`는 호출부가 넘긴 메서드 참조의 기대 타입으로 publisher의 오버로드가 정해진다.
     */
    private fun <E : DomainEvent> updateAndPublish(transition: Transition<PaymentAttempt, E>, publish: (E) -> Unit): PaymentAttempt {
        val applied = repository.update(transition.aggregate)
        transition.events.forEach(publish)
        return applied
    }

    companion object {
        private val log by logger()
        private const val CHECKOUT_LOCK_PREFIX = "payment:checkout-lock"

        fun checkoutLockKey(orderId: UUID, checkoutNo: Int): String = "$CHECKOUT_LOCK_PREFIX:$orderId:$checkoutNo"
    }
}
