package com.resilientpos.order.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_order_checkout`용 Spring Data repository. `OrderPersistenceAdapter`만 사용한다.
 */
interface OrderCheckoutJpaRepository : JpaRepository<OrderCheckoutEntity, UUID> {
    fun findAllByOrderIdOrderByCheckoutNo(orderId: UUID): List<OrderCheckoutEntity>
}
