package com.resilientpos.payment

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher,
    private val transactions: TransactionOperations,
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
     * 읽은 뒤 넣기 전에 다른 트랜잭션이 끼어드는 창은 이 읽기로 닫히지 않는다. DB의 partial unique가 닫고
     * 진 쪽은 실패한다. 그 예외는 **여기서 잡을 수 있는 것이 아니다.** `persist`가 flush를 미뤄서
     * 제약 위반이 이 메서드가 반환한 뒤 커밋에서 터지기 때문이다. 행이 둘이 되지 않는 것이
     * 지켜야 할 불변식이고 그것은 지켜진다.
     */
    @Transactional
    fun createAttempt(command: CreatePaymentAttemptCommand): CreatePaymentAttemptResult {
        val existing = repository.findAuthorization(command.orderId, command.checkoutNo)
        if (existing != null) {
            return CreatePaymentAttemptResult(existing.id, existing.status)
        }

        val transition = PaymentAttempt.create(command, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publishEvent)
        return CreatePaymentAttemptResult(saved.id, saved.status)
    }

    /**
     * 기록된 시도에 provider 승인을 요청하고 결과를 적용한다.
     *
     * provider 호출은 트랜잭션 밖이다(도메인 모델 §4.6). 트랜잭션 안에서 부르면 적용이 거기 합류해 잠금 조회가
     * 먼저 읽어 둔 낡은 값을 돌려받으므로 `NEVER`로 거절한다. 적용 트랜잭션은 [TransactionOperations]로 연다 —
     * 같은 클래스의 `@Transactional` 메서드는 프록시를 거치지 않아 열리지 않는다.
     *
     * 결말이 난 시도면 provider를 부르지 않는다(0원·재배달). 이중 청구 방어는 provider의 `PaymentId` 멱등성이다.
     */
    @Transactional(propagation = Propagation.NEVER)
    fun authorize(command: AuthorizePaymentCommand): AuthorizePaymentResult {
        val attempt = repository.findById(command.paymentId) ?: throw attemptNotFound()
        if (attempt.status != PaymentAttemptStatus.REQUESTED) {
            return AuthorizePaymentResult(attempt.id, attempt.status)
        }

        val outcome = gateway.authorize(AuthorizationRequest(attempt.id, attempt.amount))

        return requireNotNull(transactions.execute { applyOutcome(command.paymentId, outcome) })
    }

    /** 잠그고 다시 읽는다. 호출하는 동안 다른 `authorize`가 먼저 적용했으면 덮지 않고 그 결말을 돌려준다. */
    private fun applyOutcome(paymentId: PaymentId, outcome: AuthorizationOutcome): AuthorizePaymentResult {
        val current = repository.findByIdForUpdate(paymentId) ?: throw attemptNotFound()
        if (current.status != PaymentAttemptStatus.REQUESTED) {
            return AuthorizePaymentResult(current.id, current.status)
        }

        val transition = current.applyAuthorization(outcome, identifierGenerator, clock.instant())
        val applied = repository.update(transition.aggregate)
        // 발행 기록이 결과와 같은 커밋에 들어가야 하므로 이 트랜잭션 안에서 발행한다(도메인 모델 §4.3).
        transition.events.forEach(eventPublisher::publishEvent)
        return AuthorizePaymentResult(applied.id, applied.status)
    }

    private fun attemptNotFound() = DomainException(
        PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND,
        "결제 시도를 찾을 수 없습니다",
        DomainErrorKind.NOT_FOUND,
    )
}
