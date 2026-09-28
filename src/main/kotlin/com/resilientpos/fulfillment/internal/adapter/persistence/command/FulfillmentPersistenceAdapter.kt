package com.resilientpos.fulfillment.internal.adapter.persistence.command

import com.resilientpos.fulfillment.internal.domain.Fulfillment
import com.resilientpos.fulfillment.internal.domain.FulfillmentRepository
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.selectFrom
import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * `FulfillmentRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 *
 * 새 행을 `EntityManager.persist`로 넣는다. `FulfillmentEntity`는 할당식 `@Id`에 `@Version`이 없어서
 * Spring Data의 `save`가 새 행인지 알 수 없고 `merge`로 가 SELECT가 한 번 더 나간다.
 *
 * Spring Data repository를 두지 않는다. `JpaRepository`를 쓰면 `deleteAllInBatch` 같은 메서드가 공개 빈에 딸려 오는데,
 * `BaseEntity`의 soft delete 때문에 그 한 번이 모든 fulfillment를 조용히 조회에서 지운다.
 */
@Component
class FulfillmentPersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : FulfillmentRepository {

    /** `t_fulfillment`의 unique `(order_id)`라 한 건이다. `deleted_at is null`은 `BaseEntity`의 `@SoftDelete`가 붙여 준다. */
    override fun findByOrderId(orderId: UUID): Fulfillment? =
        queries.findFirst {
            selectFrom(FulfillmentEntity::class)
                .where(path(FulfillmentEntity::orderId).eq(orderId))
        }?.toDomain()

    override fun save(fulfillment: Fulfillment): Fulfillment {
        entityManager.persist(FulfillmentEntity.from(fulfillment))
        return fulfillment
    }
}
