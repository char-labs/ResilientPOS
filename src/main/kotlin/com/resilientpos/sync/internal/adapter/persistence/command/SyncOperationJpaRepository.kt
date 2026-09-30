package com.resilientpos.sync.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `t_sync_operation`용 Spring Data repository. 지금 조회와 저장은 `SyncPersistenceAdapter`가 `JdslQueryExecutor`와 `EntityManager`로 한다.
 * `JpaRepository`를 상속하지 않는다. 접수 기록은 멱등 키라 지우지 않고, 대량 삭제가 soft delete와 만나면 재전송 판정이 통째로 사라진다.
 */
interface SyncOperationJpaRepository : Repository<SyncOperationEntity, UUID>
