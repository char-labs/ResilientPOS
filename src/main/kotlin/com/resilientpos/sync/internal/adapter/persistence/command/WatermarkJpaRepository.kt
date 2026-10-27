package com.resilientpos.sync.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_sync_watermark`용 Spring Data repository. `SyncPersistenceAdapter`가 저장과 기존 행 찾기에 쓰고, 그 밖의 조회는 `JdslQueryExecutor`로 한다.
 */
interface WatermarkJpaRepository : JpaRepository<WatermarkEntity, UUID>
