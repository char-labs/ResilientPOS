package com.resilientpos.sync.internal.adapter.persistence.command

import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.sync.internal.domain.SyncOperation
import com.resilientpos.sync.internal.domain.SyncRepository
import com.resilientpos.sync.internal.domain.Watermark
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `SyncRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 *
 * 새 행은 `EntityManager.persist`로 넣는다. 두 엔티티 모두 할당식 `@Id`에 `@Version`이 없어서 Spring Data의 `save`가
 * 새 행인지 알 수 없고 `merge`로 가 SELECT가 한 번 더 나간다. watermark의 기존 행은 `EntityManager.find`로 찾는다 —
 * 같은 트랜잭션에서 잠금 조회로 이미 읽었으면 영속성 컨텍스트의 인스턴스가 돌아와 SELECT가 더 나가지 않는다.
 */
@Component
class SyncPersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : SyncRepository {

    @ReadOnlyTransactional
    override fun findWatermark(terminalId: TerminalId): Watermark? =
        queries.findFirst {
            selectFrom(WatermarkEntity::class).where(path(WatermarkEntity::terminalId).eq(terminalId.value))
        }?.toDomain()

    override fun findWatermarkForUpdate(terminalId: TerminalId): Watermark? =
        queries.findFirstForUpdate {
            selectFrom(WatermarkEntity::class).where(path(WatermarkEntity::terminalId).eq(terminalId.value))
        }?.toDomain()

    /** 트랜잭션은 Facade가 소유하고 여기서는 합류한다. 선언은 트랜잭션 없이 직접 부른 경우의 안전망이다. */
    @Transactional
    override fun saveWatermark(watermark: Watermark): Watermark {
        val existing: WatermarkEntity? = entityManager.find(WatermarkEntity::class.java, watermark.terminalId.value)
        if (existing != null) {
            existing.apply(watermark)
        } else {
            entityManager.persist(WatermarkEntity.from(watermark))
        }
        return watermark
    }

    /**
     * unique `(terminal_id, operation_id)`라 한 건이다. `deleted_at is null`은 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
     * 접수 기록은 멱등 키라 지우지 않는다. 지우는 명령이 생기면 이 조회가 삭제된 행도 보게 해야 한다 — 아니면 재전송이 `CONFLICT`가 되고
     * 새 순번의 같은 operationId는 unique에 걸린다. watermark도 같다. 삭제된 watermark 행은 잠금 조회와 `find`가 보지 못해 새 단말로
     * 시작하다가 PK에 걸린다.
     */
    override fun findOperation(terminalId: TerminalId, operationId: UUID): SyncOperation? =
        queries.findFirst {
            selectFrom(SyncOperationEntity::class).where(
                and(
                    path(SyncOperationEntity::terminalId).eq(terminalId.value),
                    path(SyncOperationEntity::operationId).eq(operationId),
                ),
            )
        }?.toDomain()

    @Transactional
    override fun saveOperation(operation: SyncOperation): SyncOperation {
        entityManager.persist(SyncOperationEntity.from(operation))
        return operation
    }
}
