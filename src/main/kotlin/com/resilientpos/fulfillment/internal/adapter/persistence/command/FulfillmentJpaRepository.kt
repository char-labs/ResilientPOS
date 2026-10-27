package com.resilientpos.fulfillment.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_fulfillment`용 Spring Data repository. `FulfillmentPersistenceAdapter`가 저장에 쓰고, 조회는 `JdslQueryExecutor`로 한다.
 */
interface FulfillmentJpaRepository : JpaRepository<FulfillmentEntity, UUID>
