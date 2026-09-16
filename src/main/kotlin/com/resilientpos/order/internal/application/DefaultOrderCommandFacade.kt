package com.resilientpos.order.internal.application

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.CreateOrderResult
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `OrderCommandFacade`의 유일한 구현. `@Transactional`을 소유하고
 * 조회 → `Order.create` → 저장 → 이벤트 발행 → 결과 변환 순서로 진행한다.
 */
@Service
class DefaultOrderCommandFacade(
    private val repository: OrderRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) : OrderCommandFacade {

    @Transactional
    override fun create(command: CreateOrderCommand): CreateOrderResult {
        val existing = repository.findByClientOrderId(command.terminalId, command.clientOrderId)
        if (existing != null) {
            return CreateOrderResult(existing.id, existing.status)
        }

        val transition = Order.create(command, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publishEvent)
        return CreateOrderResult(saved.id, saved.status)
    }
}
