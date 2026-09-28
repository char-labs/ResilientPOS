package com.resilientpos.order.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_order`용 Spring Data repository. `OrderPersistenceAdapter`가 저장에만 쓴다. 조회는 `JdslQueryExecutor`로 한다.
 */
interface OrderJpaRepository : JpaRepository<OrderEntity, UUID>
