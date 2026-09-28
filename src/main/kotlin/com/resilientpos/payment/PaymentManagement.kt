package com.resilientpos.payment

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.internal.application.PaymentEventPublisher
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.CheckoutLock
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.payment.internal.domain.PaymentGateway
import com.resilientpos.platform.logger
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations

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
    private val transactions: TransactionOperations,
    private val checkoutLock: CheckoutLock,
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
     * 같은 사실이 여러 인스턴스에 동시에 오면 읽기 전에 건 [CheckoutLock]이 줄 세운다. 늦게 온 요청은 앞 요청의
     * 커밋을 기다렸다가 그 시도를 돌려받는다. 잠금은 최선 노력이고 방어선은 DB다. 사실로 만든 승인 시도는 checkout
     * 회차당 하나라서(`retriedFrom`이 없는 행의 unique) 잠금이 통하지 않아도 두 번째가 들어가지 않는다.
     * 그때 늦은 요청은 커밋에서 실패한다(`persist`가 flush를 미룬다).
     */
    @Transactional
    fun createAttempt(command: PaymentCommand.CreateAttempt): PaymentCommand.CreateAttempt.Result {
        checkoutLock.lockUntilTransactionEnds(command.orderId, command.checkoutNo)
        val existing = repository.findAuthorization(command.orderId, command.checkoutNo)
        if (existing != null) {
            return PaymentCommand.CreateAttempt.Result(existing.id, existing.status)
        }

        val transition = PaymentAttempt.create(command, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        eventPublisher.publishAll(transition.events)
        return PaymentCommand.CreateAttempt.Result(saved.id, saved.status)
    }

    /**
     * 기록된 시도에 provider 승인을 요청하고 결과를 적용한다.
     *
     * provider 호출은 트랜잭션 밖이다(도메인 모델 §4.6). `NOT_SUPPORTED`로 호출자 트랜잭션이 있으면 멈춘다.
     * 합류하면 적용의 잠금 조회가 먼저 읽어 둔 낡은 값을 돌려받는다. 적용 트랜잭션은 [TransactionOperations]로 새로
     * 열어 반환 전에 커밋한다 — 같은 클래스의 `@Transactional` 메서드는 프록시를 거치지 않아 열리지 않는다.
     *
     * 결말이 난 시도면 provider를 부르지 않는다(0원·재배달). 이중 청구 방어는 provider의 `PaymentId` 멱등성이다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun authorize(command: PaymentCommand.Authorize): PaymentCommand.Authorize.Result {
        val attempt = repository.findById(command.paymentId) ?: throw attemptNotFound()
        if (attempt.status != PaymentAttemptStatus.REQUESTED) {
            return PaymentCommand.Authorize.Result(attempt.id, attempt.status)
        }

        val outcome = gateway.authorize(AuthorizationRequest(attempt.id, attempt.amount))

        return requireNotNull(transactions.execute { applyOutcome(command.paymentId, outcome) })
    }

    /**
     * 거절된 승인 시도를 같은 checkout 회차에서 다시 시도한다. 새 시도를 만들 뿐 provider는 부르지 않는다 —
     * 생성 사실을 받는 리스너가 [authorize]로 이어받는다.
     *
     * 대상이 그 회차의 최신 승인 시도가 아니면 이미 다시 시도한 것이라 최신 시도를 돌려준다. 응답을 잃고 다시 보낸
     * 요청이 새 provider 호출이 되지 않게 하려는 것이다. 최신인데 거절이 아니면 aggregate가 거절한다.
     *
     * 대상 행을 잠그고 최신을 읽는다. 겹친 두 요청 중 진 쪽은 앞 커밋을 기다렸다가 그 새 시도를 최신으로 본다.
     * 트랜잭션을 [TransactionOperations]로 열어 반환 뒤의 로그가 커밋 뒤의 사실이 되게 한다([authorize]와 같다).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun retryAuthorization(command: PaymentCommand.RetryAuthorization): PaymentCommand.RetryAuthorization.Result {
        val (result, retried) = requireNotNull(transactions.execute { retry(command.paymentId) })
        if (retried) {
            log.info { "결제를 다시 시도한다 declinedPaymentId=${command.paymentId.value} paymentId=${result.paymentId.value}" }
        }
        return result
    }

    private fun retry(paymentId: PaymentId): Pair<PaymentCommand.RetryAuthorization.Result, Boolean> {
        val target = repository.findByIdForUpdate(paymentId) ?: throw attemptNotFound()
        val latest = repository.findLatestAuthorization(target.orderId, target.checkoutNo)
        if (latest != null && latest.id != target.id) {
            return PaymentCommand.RetryAuthorization.Result(latest.id, latest.status) to false
        }

        val transition = target.retry(identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        eventPublisher.publishAll(transition.events)
        return PaymentCommand.RetryAuthorization.Result(saved.id, saved.status) to true
    }

    /** 잠그고 다시 읽는다. 호출하는 동안 다른 `authorize`가 먼저 적용했으면 덮지 않고 그 결말을 돌려준다. */
    private fun applyOutcome(paymentId: PaymentId, outcome: AuthorizationOutcome): PaymentCommand.Authorize.Result {
        val current = repository.findByIdForUpdate(paymentId) ?: throw attemptNotFound()
        if (current.status != PaymentAttemptStatus.REQUESTED) {
            return PaymentCommand.Authorize.Result(current.id, current.status)
        }

        val transition = current.applyAuthorization(outcome, identifierGenerator, clock.instant())
        val applied = repository.update(transition.aggregate)
        // 발행 기록이 결과와 같은 커밋에 들어가야 하므로 이 트랜잭션 안에서 발행한다(도메인 모델 §4.3).
        eventPublisher.publishAll(transition.events)
        return PaymentCommand.Authorize.Result(applied.id, applied.status)
    }

    private fun attemptNotFound() = DomainException(
        PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND,
        "결제 시도를 찾을 수 없습니다",
        DomainErrorKind.NOT_FOUND,
    )

    companion object {
        private val log by logger()
    }
}
