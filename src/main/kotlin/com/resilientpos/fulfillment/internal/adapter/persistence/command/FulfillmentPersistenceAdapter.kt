package com.resilientpos.fulfillment.internal.adapter.persistence.command

import com.resilientpos.fulfillment.internal.domain.Fulfillment
import com.resilientpos.fulfillment.internal.domain.FulfillmentRepository
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.selectFrom
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * `FulfillmentRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 *
 * 새 행은 [FulfillmentJpaRepository.save]로 넣는다. 할당식 `@Id`에 `@Version`이 없어 `merge`로 가 SELECT가 하나 더 나간다.
 * 트랜잭션은 Facade가 소유하고 `save`는 거기에 합류한다.
 */
@Component
class FulfillmentPersistenceAdapter(
    private val jpaRepository: FulfillmentJpaRepository,
    private val queries: JdslQueryExecutor,
) : FulfillmentRepository {

    /** `t_fulfillment`의 unique `(order_id)`라 한 건이다. `deleted_at is null`은 `BaseEntity`의 `@SoftDelete`가 붙여 준다. */
    override fun findByOrderId(orderId: UUID): Fulfillment? =
        queries.findFirst {
            selectFrom(FulfillmentEntity::class)
                .where(path(FulfillmentEntity::orderId).eq(orderId))
        }?.toDomain()

    override fun save(fulfillment: Fulfillment): Fulfillment {
        jpaRepository.save(FulfillmentEntity.from(fulfillment))
        return fulfillment
    }
}
