package com.resilientpos.order.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_order`용 Spring Data repository. `OrderPersistenceAdapter`만 사용한다.
 */
interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
    fun findByTerminalIdAndClientOrderId(terminalId: UUID, clientOrderId: UUID): OrderEntity?
}
