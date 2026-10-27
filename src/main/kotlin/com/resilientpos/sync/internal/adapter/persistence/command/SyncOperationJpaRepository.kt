package com.resilientpos.sync.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_sync_operation`용 Spring Data repository. `SyncPersistenceAdapter`가 저장에 쓰고, 조회는 `JdslQueryExecutor`로 한다.
 */
interface SyncOperationJpaRepository : JpaRepository<SyncOperationEntity, UUID>
