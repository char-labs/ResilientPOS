package com.resilientpos.order.internal.adapter.persistence.command

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

/**
 * `t_order`용 Spring Data repository. `OrderPersistenceAdapter`만 사용한다.
 */
interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
    fun findByTerminalIdAndClientOrderId(terminalId: UUID, clientOrderId: UUID): OrderEntity?

    /** PostgreSQL에서는 `select ... for update`다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: UUID): OrderEntity?
}
