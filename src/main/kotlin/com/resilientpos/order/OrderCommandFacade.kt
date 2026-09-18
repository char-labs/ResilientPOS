package com.resilientpos.order

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 주문 생성 use case의 진입점. 인터페이스를 따로 두지 않는 구체 클래스다. `@Transactional`을 소유하고
 * 조회 → `Order.create` → 저장 → 이벤트 발행 → 결과 변환 순서로 진행한다.
 */
@Service
class OrderCommandFacade(
    private val repository: OrderRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun create(command: CreateOrderCommand): CreateOrderResult {
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
