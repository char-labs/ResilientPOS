package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.isUniqueViolationOf
import com.resilientpos.platform.selectFrom
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import java.util.UUID
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * `OrderRepository`의 JPA 구현. 엔티티 사이에 연관관계 매핑이 없으므로 주문과 line의 조립과 변경 반영을 여기서 한다.
 * 새 행이면 `from`, 기존 행이면 `apply`로 저장하고 line과 checkout은 ID로 맞춘다.
 * line과 checkout은 `@Version`이 없다. 도메인 ID를 저장 전에 만들기 때문에 Spring Data의 `save`는 이런 새 엔티티를 기존 행으로 보고
 * `merge`해서 SELECT가 한 번 더 나간다. 그래서 새 자식 행은 `EntityManager.persist`로 넣는다. 기존 행은 같은 트랜잭션에서 읽은 관리 엔티티라
 * `apply`만 하면 flush가 UPDATE한다. 조회는 [JdslQueryExecutor]로 한다. 저장할 때 기존 주문 행은 `EntityManager.find`로 찾는다.
 * 같은 트랜잭션에서 잠금 조회로 이미 읽었으면 영속성 컨텍스트의 인스턴스가 돌아와 SELECT가 더 나가지 않는다.
 * 호출 순서와 무관하게 새 자식 행의 INSERT가 soft delete UPDATE보다 먼저 flush된다(SQL 로그로 확인).
 * `t_order_line`에 지금은 PK 말고 unique가 없어서 지워진 행의 `line_no`를 새 행이 물려받아도 충돌하지 않는다.
 * partial unique를 더하려면 이 순서를 먼저 본다.
 */
@Repository
class OrderPersistenceAdapter(
    private val jpaRepository: OrderJpaRepository,
    private val lineJpaRepository: OrderLineJpaRepository,
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : OrderRepository {

    /** 주문과 line·checkout을 읽는 세 쿼리가 호출자 트랜잭션에 합류한다. 없으면 트랜잭션 없이 한 EntityManager로 읽는다. */
    @ReadOnlyTransactional
    override fun findById(id: OrderId): Order? =
        queries.findFirst {
            selectFrom(OrderEntity::class).where(path(OrderEntity::id).eq(id.value))
        }?.let(::toDomain)

    override fun findByIdForUpdate(id: OrderId): Order? =
        queries.findFirstForUpdate {
            selectFrom(OrderEntity::class).where(path(OrderEntity::id).eq(id.value))
        }?.let(::toDomain)

    override fun findByIdAndStoreIdForUpdate(id: OrderId, storeId: StoreId): Order? =
        queries.findFirstForUpdate {
            selectFrom(OrderEntity::class).where(
                and(
                    path(OrderEntity::id).eq(id.value),
                    path(OrderEntity::storeId).eq(storeId.value),
                ),
            )
        }?.let(::toDomain)

    override fun findByClientOrderIdForUpdate(terminalId: TerminalId, clientOrderId: ClientOrderId): Order? =
        queries.findFirstForUpdate {
            selectFrom(OrderEntity::class).where(
                and(
                    path(OrderEntity::terminalId).eq(terminalId.value),
                    path(OrderEntity::clientOrderId).eq(clientOrderId.value),
                ),
            )
        }?.let(::toDomain)

    override fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order? =
        queries.findFirst {
            selectFrom(OrderEntity::class).where(
                and(
                    path(OrderEntity::terminalId).eq(terminalId.value),
                    path(OrderEntity::clientOrderId).eq(clientOrderId.value),
                ),
            )
        }?.let(::toDomain)

    /**
     * 트랜잭션은 Facade가 소유하고 여기서는 합류한다. 선언은 트랜잭션 없이 직접 부른 경우의 안전망이다.
     * 없으면 주문 행만 자기 트랜잭션으로 커밋되고 `persist`가 실패해 line 없는 주문이 남는다.
     */
    @Transactional
    override fun save(order: Order): Order {
        val orderId = order.id.value
        val existing: OrderEntity? = entityManager.find(OrderEntity::class.java, orderId)
        jpaRepository.save(existing?.also { it.apply(order) } ?: OrderEntity.from(order))

        val existingLines = if (existing == null) emptyMap()
        else findLines(orderId).associateBy { it.id }
        val lines = order.lines.mapIndexed { lineNo, line ->
            existingLines[line.id.value]?.also { it.apply(lineNo, line) } ?: OrderLineEntity.from(orderId, lineNo, line)
        }
        lineJpaRepository.deleteAll(existingLines.values - lines.toSet())
        (lines - existingLines.values.toSet()).forEach(entityManager::persist)

        // checkout은 확정 뒤에 바뀌지 않는다. 없는 ID만 넣고 갱신·삭제하지 않는다.
        val storedCheckoutIds = if (existing == null) emptySet()
        else findCheckouts(orderId).map { it.id }.toSet()
        order.checkouts.filter { it.id.value !in storedCheckoutIds }
            .map { OrderCheckoutEntity.from(orderId, it) }
            .forEach(entityManager::persist)
        if (existing == null) flushNewOrder(order)
        return order
    }

    private fun flushNewOrder(order: Order) {
        try {
            entityManager.flush()
        } catch (e: PersistenceException) {
            // 사전 조회 뒤 같은 키의 주문이 먼저 들어온 경합이라 도메인 거절이 아니다. 커밋이 호출자(sync 항목) 트랜잭션에 있어도 여기서 가리도록 저장에서 flush한다.
            if (e.isUniqueViolationOf(CLIENT_ORDER_CONSTRAINT)) {
                throw ConcurrencyFailureException(
                    "같은 키의 주문이 동시에 저장됐다 terminalId=${order.terminalId.value} clientOrderId=${order.clientOrderId.value}",
                    e,
                )
            }
            throw e
        }
    }

    private fun toDomain(entity: OrderEntity): Order =
        entity.toDomain(findLines(entity.id), findCheckouts(entity.id))

    private fun findLines(orderId: UUID): List<OrderLineEntity> =
        queries.findAll {
            selectFrom(OrderLineEntity::class)
                .where(path(OrderLineEntity::orderId).eq(orderId))
                .orderBy(path(OrderLineEntity::lineNo).asc())
        }

    private fun findCheckouts(orderId: UUID): List<OrderCheckoutEntity> =
        queries.findAll {
            selectFrom(OrderCheckoutEntity::class)
                .where(path(OrderCheckoutEntity::orderId).eq(orderId))
                .orderBy(path(OrderCheckoutEntity::checkoutNo).asc())
        }

    private companion object {
        const val CLIENT_ORDER_CONSTRAINT = "t_order_terminal_id_client_order_id_key"
    }
}
