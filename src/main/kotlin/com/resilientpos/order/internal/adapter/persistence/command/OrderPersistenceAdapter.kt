package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.terminal.TerminalId
import org.springframework.stereotype.Repository

/**
 * `OrderRepository`의 JPA 구현. 새 행이면 `from`, 기존 행이면 `apply`로 저장한다.
 */
@Repository
class OrderPersistenceAdapter(
    private val jpaRepository: OrderJpaRepository,
) : OrderRepository {

    override fun findById(id: OrderId): Order? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order? =
        jpaRepository.findByTerminalIdAndClientOrderId(terminalId.value, clientOrderId.value)?.toDomain()

    override fun save(order: Order): Order {
        val existing = jpaRepository.findById(order.id.value).orElse(null)
        val entity = if (existing != null) {
            existing.apply(order)
            existing
        } else {
            OrderJpaEntity.from(order)
        }
        return jpaRepository.save(entity).toDomain()
    }
}
