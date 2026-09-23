package com.resilientpos.payment

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
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
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * 한 checkout의 승인 시도를 만든다. 같은 이벤트가 다시 와도 시도를 두 번 만들지 않는다.
     *
     * 이미 진행 중인 승인이 있으면 그것을 그대로 돌려주고 이벤트를 다시 내지 않는다. 다시 내면
     * 소비자가 같은 사실을 두 번 보게 되고, 이 시도는 새로 일어난 일이 아니다.
     *
     * 읽은 뒤 넣기 전에 다른 트랜잭션이 끼어드는 창은 이 읽기로 닫히지 않는다. DB의 partial unique가 닫고
     * 진 쪽은 실패한다. 그 예외는 **여기서 잡을 수 있는 것이 아니다.** `persist`가 flush를 미뤄서
     * 제약 위반이 이 메서드가 반환한 뒤 커밋에서 터지기 때문이다. 행이 둘이 되지 않는 것이
     * 지켜야 할 불변식이고 그것은 지켜진다.
     */
    @Transactional
    fun createAttempt(command: CreatePaymentAttemptCommand): CreatePaymentAttemptResult {
        val inflight = repository.findInflightAuthorization(command.orderId, command.checkoutNo)
        if (inflight != null) {
            return CreatePaymentAttemptResult(inflight.id, inflight.status)
        }

        val transition = PaymentAttempt.create(command, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publishEvent)
        return CreatePaymentAttemptResult(saved.id, saved.status)
    }

    /**
     * 기록된 시도에 provider 승인을 요청하고 결과를 적용한다.
     *
     * **결말이 난 시도면 provider를 부르지 않고 돌아선다.** 0원 시도가 만들어지자마자 `APPROVED`인 것과
     * 같은 사실이 다시 배달된 경우가 둘 다 이 문으로 막힌다. 이미 승인된 결제를 provider에 또 요청하면
     * 이중 청구가 된다.
     *
     * **provider 호출이 이 트랜잭션 안에서 일어난다.** 호출이 끝날 때까지 DB 커넥션을 쥐고 있다는 뜻이다.
     * `@ApplicationModuleListener`가 자기 트랜잭션을 여는 구조라 호출만 밖으로 뺄 자리가 없다.
     * 실제 provider가 붙어 지연이 생기면 커넥션 풀과 함께 다시 본다.
     *
     * 읽은 뒤 적용하기 전에 다른 트랜잭션이 같은 시도에 결과를 적용하는 창은 여기서 닫히지 않는다.
     * 지금은 재발행이 꺼져 있어 같은 사실이 동시에 두 번 배달되지 않으므로 그 창이 열리지 않는다.
     * 재발행을 켜는 PR이 업무 수준 멱등 근거를 함께 정한다.
     */
    @Transactional
    fun authorize(command: AuthorizePaymentCommand): AuthorizePaymentResult {
        val attempt = repository.findById(command.paymentId)
            ?: throw DomainException(
                PaymentAttempt.PAYMENT_ATTEMPT_NOT_FOUND,
                "결제 시도를 찾을 수 없습니다",
                DomainErrorKind.NOT_FOUND,
            )

        if (attempt.status != PaymentAttemptStatus.REQUESTED) {
            return AuthorizePaymentResult(attempt.id, attempt.status)
        }

        val outcome = gateway.authorize(AuthorizationRequest(attempt.id, attempt.amount))
        val transition = attempt.applyAuthorization(outcome, identifierGenerator, clock.instant())
        val applied = repository.update(transition.aggregate)
        transition.events.forEach(eventPublisher::publishEvent)
        return AuthorizePaymentResult(applied.id, applied.status)
    }
}
