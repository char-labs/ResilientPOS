package com.resilientpos.order.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_order_checkout`용 Spring Data repository. 지금 조회는 `OrderPersistenceAdapter`가 `JdslQueryExecutor`로 한다.
 */
interface OrderCheckoutJpaRepository : JpaRepository<OrderCheckoutEntity, UUID>
