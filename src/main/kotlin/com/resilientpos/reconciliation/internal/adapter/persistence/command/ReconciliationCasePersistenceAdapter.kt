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
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * `ReconciliationCaseRepository`의 JPA 구현. 엔티티 사이에 연관관계 매핑이 없으므로 case와 감사 기록의 조립과 변경 반영을 여기서 한다.
 * 새 case면 루트와 감사 줄을 `EntityManager.persist`로 넣고, 기존 case면 관리 엔티티에 `apply`하고 아직 없는 감사 줄만 `persist`한다.
 * 두 엔티티 모두 `@Version`이 없어 Spring Data의 `save`를 쓰면 `merge`로 가 SELECT가 한 번 더 나간다. 감사 줄은 넣기만 하고 바꾸거나 지우지 않는다.
 * `deleted_at is null`은 조회마다 적지 않아도 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
 */
@Repository
class ReconciliationCasePersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : ReconciliationCaseRepository {

    /** case와 감사 기록을 읽는 두 쿼리가 호출자 트랜잭션에 합류한다. 없으면 트랜잭션 없이 한 EntityManager로 읽는다. */
    @ReadOnlyTransactional
    override fun findByPaymentId(paymentId: PaymentId): ReconciliationCase? =
        queries.findFirst {
            selectFrom(ReconciliationCaseEntity::class).where(path(ReconciliationCaseEntity::paymentId).eq(paymentId.value))
        }?.let(::toDomain)

    override fun findByIdAndStoreIdForUpdate(id: ReconciliationCaseId, storeId: StoreId): ReconciliationCase? =
        queries.findFirstForUpdate {
            selectFrom(ReconciliationCaseEntity::class).where(
                and(
                    path(ReconciliationCaseEntity::id).eq(id.value),
                    path(ReconciliationCaseEntity::storeId).eq(storeId.value),
                ),
            )
        }?.let(::toDomain)

    /**
     * 트랜잭션은 Facade가 소유하고 여기서는 합류한다. 선언은 트랜잭션 없이 직접 부른 경우의 안전망이다.
     * 없으면 case 행만 자기 트랜잭션으로 커밋되고 감사 줄의 `persist`가 실패해 감사 없는 case가 남는다.
     */
    @Transactional
    override fun save(reconciliationCase: ReconciliationCase): ReconciliationCase {
        val caseId = reconciliationCase.id.value
        val existing: ReconciliationCaseEntity? = entityManager.find(ReconciliationCaseEntity::class.java, caseId)
        if (existing == null) {
            entityManager.persist(ReconciliationCaseEntity.from(reconciliationCase))
        } else {
            existing.apply(reconciliationCase)
        }

        val storedActionIds = if (existing == null) emptySet() else findActions(caseId).map { it.id }.toSet()
        reconciliationCase.actions.filter { it.id.value !in storedActionIds }
            .map { ReconciliationCaseActionEntity.from(caseId, it) }
            .forEach(entityManager::persist)
        return reconciliationCase
    }

    private fun toDomain(entity: ReconciliationCaseEntity): ReconciliationCase =
        entity.toDomain(findActions(entity.id).map { it.toDomain() })

    /** 감사 줄은 `id`가 UUID v7이라 `id` 정렬이 곧 기록 순서다. */
    private fun findActions(caseId: UUID): List<ReconciliationCaseActionEntity> =
        queries.findAll {
            selectFrom(ReconciliationCaseActionEntity::class)
                .where(path(ReconciliationCaseActionEntity::caseId).eq(caseId))
                .orderBy(path(ReconciliationCaseActionEntity::id).asc())
        }
}
