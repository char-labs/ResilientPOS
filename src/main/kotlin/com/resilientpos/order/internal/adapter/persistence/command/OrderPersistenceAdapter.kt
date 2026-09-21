package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * `OrderRepository`의 JPA 구현. 엔티티 사이에 연관관계 매핑이 없으므로 주문과 line의 조립과 변경 반영을 여기서 한다.
 * 새 행이면 `from`, 기존 행이면 `apply`로 저장하고 line과 checkout은 ID로 맞춘다.
 * line과 checkout은 `@Version`이 없다. 도메인 ID를 저장 전에 만들기 때문에 Spring Data의 `save`는 이런 새 엔티티를 기존 행으로 보고
 * `merge`해서 SELECT가 한 번 더 나간다. 그래서 새 자식 행은 `EntityManager.persist`로 넣는다. 기존 행은 같은 트랜잭션에서 읽은 관리 엔티티라
 * `apply`만 하면 flush가 UPDATE한다.
 */
@Repository
class OrderPersistenceAdapter(
    private val jpaRepository: OrderJpaRepository,
    private val lineJpaRepository: OrderLineJpaRepository,
    private val checkoutJpaRepository: OrderCheckoutJpaRepository,
    private val entityManager: EntityManager,
) : OrderRepository {

    override fun findById(id: OrderId): Order? =
        jpaRepository.findById(id.value).orElse(null)?.let(::toDomain)

    override fun findByIdForUpdate(id: OrderId): Order? =
        jpaRepository.findWithLockById(id.value)?.let(::toDomain)

    override fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order? =
        jpaRepository.findByTerminalIdAndClientOrderId(terminalId.value, clientOrderId.value)?.let(::toDomain)

    /**
     * 트랜잭션은 Facade가 소유하고 여기서는 합류한다. 선언은 트랜잭션 없이 직접 부른 경우의 안전망이다.
     * 없으면 주문 행만 자기 트랜잭션으로 커밋되고 `persist`가 실패해 line 없는 주문이 남는다.
     */
    @Transactional
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
        (lines - existingLines.values.toSet()).forEach(entityManager::persist)

        // checkout은 확정 뒤에 바뀌지 않는다. 없는 ID만 넣고 갱신·삭제하지 않는다.
        val storedCheckoutIds = if (existing == null) emptySet()
        else checkoutJpaRepository.findAllByOrderIdOrderByCheckoutNo(orderId).map { it.id }.toSet()
        order.checkouts.filter { it.id.value !in storedCheckoutIds }
            .map { OrderCheckoutEntity.from(orderId, it) }
            .forEach(entityManager::persist)
        return order
    }

    private fun toDomain(entity: OrderEntity): Order =
        entity.toDomain(
            lineJpaRepository.findAllByOrderIdOrderByLineNo(entity.id),
            checkoutJpaRepository.findAllByOrderIdOrderByCheckoutNo(entity.id),
        )
}
