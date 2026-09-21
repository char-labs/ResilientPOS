package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.terminal.TerminalId
import org.springframework.stereotype.Repository

/**
 * `OrderRepository`의 JPA 구현. 엔티티 사이에 연관관계 매핑이 없으므로 주문과 line의 조립과 변경 반영을 여기서 한다.
 * 새 행이면 `from`, 기존 행이면 `apply`로 저장하고 line과 checkout은 ID로 맞춘다.
 */
@Repository
class OrderPersistenceAdapter(
    private val jpaRepository: OrderJpaRepository,
    private val lineJpaRepository: OrderLineJpaRepository,
    private val checkoutJpaRepository: OrderCheckoutJpaRepository,
) : OrderRepository {

    override fun findById(id: OrderId): Order? =
        jpaRepository.findById(id.value).orElse(null)?.let(::toDomain)

    override fun findByIdForUpdate(id: OrderId): Order? =
        jpaRepository.findWithLockById(id.value)?.let(::toDomain)

    override fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order? =
        jpaRepository.findByTerminalIdAndClientOrderId(terminalId.value, clientOrderId.value)?.let(::toDomain)

    override fun save(order: Order): Order {
        val orderId = order.id.value
        val existing = jpaRepository.findById(orderId).orElse(null)
        jpaRepository.save(existing?.also { it.apply(order) } ?: OrderEntity.from(order))

        val existingLines = if (existing == null) emptyMap()
        else lineJpaRepository.findAllByOrderIdOrderByLineNo(orderId).associateBy { it.id }
        val lines = order.lines.mapIndexed { lineNo, line ->
            existingLines[line.id.value]?.also { it.apply(lineNo, line) } ?: OrderLineEntity.from(orderId, lineNo, line)
        }
        lineJpaRepository.deleteAll(existingLines.values - lines.toSet())
        lineJpaRepository.saveAll(lines)

        // checkout은 확정 뒤에 바뀌지 않는다. 없는 ID만 넣고 갱신·삭제하지 않는다.
        val storedCheckoutIds = if (existing == null) emptySet()
        else checkoutJpaRepository.findAllByOrderIdOrderByCheckoutNo(orderId).map { it.id }.toSet()
        checkoutJpaRepository.saveAll(
            order.checkouts.filter { it.id.value !in storedCheckoutIds }.map { OrderCheckoutEntity.from(orderId, it) },
        )
        return order
    }

    private fun toDomain(entity: OrderEntity): Order =
        entity.toDomain(
            lineJpaRepository.findAllByOrderIdOrderByLineNo(entity.id),
            checkoutJpaRepository.findAllByOrderIdOrderByCheckoutNo(entity.id),
        )
}
