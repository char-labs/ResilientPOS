package com.resilientpos.order.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_order_line`용 Spring Data repository. `OrderPersistenceAdapter`만 사용한다.
 */
interface OrderLineJpaRepository : JpaRepository<OrderLineEntity, UUID> {
    fun findAllByOrderIdOrderByLineNo(orderId: UUID): List<OrderLineEntity>
}
