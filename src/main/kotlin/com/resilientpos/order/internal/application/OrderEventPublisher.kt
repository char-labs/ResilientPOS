package com.resilientpos.order.internal.application

import com.resilientpos.contract.OrderPaid
import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.order.OrderEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * `order`가 내는 이벤트를 구체 타입으로 발행한다. Facade 트랜잭션 안에서 불러야 발행 기록이 같은 커밋에 들어간다.
 *
 * 이벤트 타입마다 메서드를 따로 둔다. IDE는 `publishEvent` 인자의 정적 타입으로 리스너와 발행 지점을 잇는데,
 * `DomainEvent`로 넘기면 리스너마다 발행 지점을 찾지 못한다. Facade는 전이가 내는 이벤트 타입으로 알맞은 메서드를 부른다.
 */
@Component
class OrderEventPublisher(
    private val publisher: ApplicationEventPublisher,
) {

    fun publish(event: OrderEvent.Created) = publisher.publishEvent(event)

    fun publish(event: OrderPaymentRequested) = publisher.publishEvent(event)

    fun publish(event: OrderPaid) = publisher.publishEvent(event)
}
