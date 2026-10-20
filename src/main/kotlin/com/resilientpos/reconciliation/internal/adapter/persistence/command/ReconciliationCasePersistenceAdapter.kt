package com.resilientpos.reconciliation.internal.adapter.persistence.command

import com.resilientpos.payment.PaymentId
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.reconciliation.internal.domain.ReconciliationCaseRepository
import com.resilientpos.store.StoreId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * `ReconciliationCaseRepository`의 JPA 구현. case는 한 행이라 새 case면 `EntityManager.persist`로 넣고, 기존 case면 관리 엔티티에 `apply`한다.
 * 엔티티에 `@Version`이 없어 Spring Data의 `save`를 쓰면 `merge`로 가 SELECT가 한 번 더 나간다.
 * `deleted_at is null`은 조회마다 적지 않아도 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
 */
@Repository
class ReconciliationCasePersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : ReconciliationCaseRepository {

    /** 호출자 트랜잭션에 합류한다. 없으면 트랜잭션 없이 한 EntityManager로 읽는다. */
    @ReadOnlyTransactional
    override fun findByPaymentId(paymentId: PaymentId): ReconciliationCase? =
        queries.findFirst {
            selectFrom(ReconciliationCaseEntity::class).where(path(ReconciliationCaseEntity::paymentId).eq(paymentId.value))
        }?.toDomain()

    override fun findByIdAndStoreIdForUpdate(id: ReconciliationCaseId, storeId: StoreId): ReconciliationCase? =
        queries.findFirstForUpdate {
            selectFrom(ReconciliationCaseEntity::class).where(
                and(
                    path(ReconciliationCaseEntity::id).eq(id.value),
                    path(ReconciliationCaseEntity::storeId).eq(storeId.value),
                ),
            )
        }?.toDomain()

    /** 트랜잭션은 Facade가 소유하고 여기서는 합류한다. 선언은 트랜잭션 없이 직접 부른 경우의 안전망이다. */
    @Transactional
    override fun save(reconciliationCase: ReconciliationCase): ReconciliationCase {
        val caseId = reconciliationCase.id.value
        val existing: ReconciliationCaseEntity? = entityManager.find(ReconciliationCaseEntity::class.java, caseId)
        if (existing == null) {
            entityManager.persist(ReconciliationCaseEntity.from(reconciliationCase))
        } else {
            existing.apply(reconciliationCase)
        }

        return reconciliationCase
    }
}
