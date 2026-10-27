package com.resilientpos.owner.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_owner`용 Spring Data repository. `OwnerPersistenceAdapter`가 저장에 쓰고, 조회는 `JdslQueryExecutor`로 한다.
 */
interface OwnerJpaRepository : JpaRepository<OwnerEntity, UUID>
