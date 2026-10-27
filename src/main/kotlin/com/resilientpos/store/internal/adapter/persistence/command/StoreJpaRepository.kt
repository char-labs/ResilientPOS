package com.resilientpos.store.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_store`용 Spring Data repository. `StorePersistenceAdapter`가 저장에 쓴다.
 */
interface StoreJpaRepository : JpaRepository<StoreEntity, UUID>
