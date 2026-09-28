package com.resilientpos.payment.internal.application

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.foundation.DomainEvent
import com.resilientpos.payment.PaymentEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionStatus

/**
 * `payment`가 내는 이벤트를 구체 타입으로 발행한다.
 *
 * 발행 기록이 업무 상태와 같은 커밋에 들어가야 하므로 트랜잭션 안에서만 부른다. `transaction`은 쓰지 않고 그 증거로만
 * 받는다. 트랜잭션 밖에서 발행하면 기록만 남고 리스너가 돌지 않아 예외 없이 미완료로 쌓이는데, 인자로 받으면 그런 호출이
 * 컴파일되지 않는다.
 *
 * 타입마다 `publishEvent`를 따로 부르는 이유: IDE는 `publishEvent` 인자의 정적 타입으로 리스너와 발행 지점을 잇는다.
 * `DomainEvent`로 넘기면 리스너마다 발행 지점을 찾지 못한다.
 */
@Component
class PaymentEventPublisher(
    private val publisher: ApplicationEventPublisher,
) {

    fun publishAll(transaction: TransactionStatus, events: List<DomainEvent>) = events.forEach { event ->
        when (event) {
            is PaymentEvent.AttemptCreated -> publish(event)
            is PaymentEvent.Declined -> publish(event)
            is PaymentEvent.Unknown -> publish(event)
            is PaymentApproved -> publish(event)
            else -> throw IllegalArgumentException("payment가 내지 않는 이벤트다: ${event::class.qualifiedName}")
        }
    }

    private fun publish(event: PaymentEvent.AttemptCreated) = publisher.publishEvent(event)

    private fun publish(event: PaymentEvent.Declined) = publisher.publishEvent(event)

    private fun publish(event: PaymentEvent.Unknown) = publisher.publishEvent(event)

    private fun publish(event: PaymentApproved) = publisher.publishEvent(event)
}
