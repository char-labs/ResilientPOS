package com.resilientpos.order.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_order_line`용 Spring Data repository. `OrderPersistenceAdapter`가 새 line 저장과 삭제에 쓴다. 조회는 `JdslQueryExecutor`로 한다.
 */
interface OrderLineJpaRepository : JpaRepository<OrderLineEntity, UUID>
