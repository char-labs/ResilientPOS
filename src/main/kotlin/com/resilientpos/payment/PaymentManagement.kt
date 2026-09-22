package com.resilientpos.payment

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `payment` 모듈의 유일한 진입점. 결제 정본 상태를 바꾸는 일은 전부 여기를 거친다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 구현이 둘이 될 때 뽑는다.
 */
@Component
class PaymentManagement(
    private val repository: PaymentAttemptRepository,
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
}
