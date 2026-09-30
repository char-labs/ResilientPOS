package com.resilientpos.sync.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `t_sync_watermark`용 Spring Data repository. 지금 조회와 저장은 `SyncPersistenceAdapter`가 `JdslQueryExecutor`와 `EntityManager`로 한다.
 * `JpaRepository`를 상속하지 않는다. 대량 삭제 메서드가 soft delete와 만나 모든 watermark를 조회에서 지우면 단말 전부가 순번 1부터 다시 시작한다.
 */
interface WatermarkJpaRepository : Repository<WatermarkEntity, UUID>
