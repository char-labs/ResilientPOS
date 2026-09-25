package com.resilientpos.fulfillment.internal.adapter.persistence.command

import com.resilientpos.fulfillment.internal.domain.Fulfillment
import com.resilientpos.fulfillment.internal.domain.FulfillmentRepository
import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * `FulfillmentRepository`의 JPA 구현.
 *
 * 새 행을 `EntityManager.persist`로 넣는다. `FulfillmentEntity`는 할당식 `@Id`에 `@Version`이 없어서
 * Spring Data의 `save`가 새 행인지 알 수 없고 `merge`로 가 SELECT가 한 번 더 나간다.
 */
@Component
class FulfillmentPersistenceAdapter(
    private val jpaRepository: FulfillmentJpaRepository,
    private val entityManager: EntityManager,
) : FulfillmentRepository {

    override fun findByOrderId(orderId: UUID): Fulfillment? =
        jpaRepository.findByOrderId(orderId)?.toDomain()

    override fun save(fulfillment: Fulfillment): Fulfillment {
        entityManager.persist(FulfillmentEntity.from(fulfillment))
        return fulfillment
    }
}
