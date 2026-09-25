package com.resilientpos.fulfillment.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `JpaRepository`를 상속하지 않는다. 그러면 `deleteAllInBatch` 같은 메서드가 공개 빈에 딸려 오는데,
 * `BaseEntity`의 soft delete 때문에 그 한 번이 모든 fulfillment를 조용히 조회에서 지운다.
 */
interface FulfillmentJpaRepository : Repository<FulfillmentEntity, UUID> {

    /** 그 주문의 fulfillment를 읽는다. `deleted_at is null`은 `BaseEntity`의 `@SoftDelete`가 붙여 준다. */
    fun findByOrderId(orderId: UUID): FulfillmentEntity?
}
