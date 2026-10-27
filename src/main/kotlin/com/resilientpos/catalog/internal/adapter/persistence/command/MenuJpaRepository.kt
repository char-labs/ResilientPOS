package com.resilientpos.catalog.internal.adapter.persistence.command

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

/**
 * `t_menu`용 Spring Data repository. `MenuPersistenceAdapter`가 저장과 갱신할 행 찾기에 쓴다.
 */
interface MenuJpaRepository : JpaRepository<MenuEntity, UUID> {

    /**
     * 내용이 같은 수정이 version을 한 번 올리게 한다. 내용이 바뀌는 수정에는 쓰지 않는다. dirty UPDATE와 겹치면 version이 둘 오른다.
     * 같은 트랜잭션에서 이미 읽은 엔티티에 잠금만 더한다.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    fun findForVersionBumpByStoreId(storeId: UUID): MenuEntity?
}
