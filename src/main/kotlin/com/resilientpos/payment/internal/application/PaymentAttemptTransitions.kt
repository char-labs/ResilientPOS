package com.resilientpos.payment.internal.application

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Transition
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import java.time.Clock
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `PaymentManagement`가 트랜잭션 밖에서 일한 뒤 따로 여는 트랜잭션 단위다.
 * 같은 클래스의 `@Transactional` 메서드는 프록시를 거치지 않아 열리지 않으므로 다른 빈에 둔다.
 * 호출하는 쪽이 `NOT_SUPPORTED`라 두 메서드는 늘 새 트랜잭션에서 돌고 반환 전에 커밋한다.
 */
@Component
class PaymentAttemptTransitions(
    private val repository: PaymentAttemptRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: PaymentEventPublisher,
) {

    /** 잠그고 다시 읽는다. 호출하는 동안 다른 `authorize`가 먼저 적용했으면 덮지 않고 그 결말을 돌려준다. */
    @Transactional
    fun applyOutcome(paymentId: PaymentId, outcome: AuthorizationOutcome): PaymentCommand.Authorize.Result {
        val current = repository.findByIdForUpdate(paymentId) ?: throw PaymentAttempt.notFound()
        if (current.status != PaymentAttemptStatus.REQUESTED) {
            return PaymentCommand.Authorize.Result(current.id, current.status)
        }

        val now = clock.instant()
        val applied = when (outcome) {
            is AuthorizationOutcome.Approved ->
                updateAndPublish(current.approve(outcome.providerReference, identifierGenerator, now), eventPublisher::publish)
            is AuthorizationOutcome.Declined ->
                updateAndPublish(current.decline(outcome.providerReference, identifierGenerator, now), eventPublisher::publish)
            AuthorizationOutcome.Unknown ->
                updateAndPublish(current.markUnknown(identifierGenerator, now), eventPublisher::publish)
        }
        return PaymentCommand.Authorize.Result(applied.id, applied.status)
    }

    /**
     * 거절된 시도의 행을 잠그고 그 회차의 최신 승인 시도를 읽는다. 최신이 아니면 이미 다시 시도한 것이라 최신 시도를 돌려준다.
     * 새 시도를 만들었으면 `true`를 함께 돌려준다.
     */
    @Transactional
    fun retry(paymentId: PaymentId): Pair<PaymentCommand.RetryAuthorization.Result, Boolean> {
        val target = repository.findByIdForUpdate(paymentId) ?: throw PaymentAttempt.notFound()
        val latest = repository.findLatestAuthorization(target.orderId, target.checkoutNo)
        if (latest != null && latest.id != target.id) {
            return PaymentCommand.RetryAuthorization.Result(latest.id, latest.status) to false
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
}
