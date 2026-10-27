package com.resilientpos.catalog.internal.adapter.persistence.command

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

/**
 * `t_menu`용 Spring Data repository. `MenuPersistenceAdapter`가 저장과 갱신할 행 찾기에 쓴다.
 */
interface MenuJpaRepository : JpaRepository<MenuEntity, UUID> {

    /** 엔티티를 컨텍스트에 올리지 않고 문서와 version만 읽는다. 내용이 같은지 가린 뒤에 어느 쪽으로 읽을지 정한다. */
    fun findSnapshotByStoreId(storeId: UUID): MenuSnapshot?

    /**
     * 내용이 같은 수정이 version을 한 번 올리게 한다. 엔티티를 처음 읽을 때 건 잠금만 효과가 있다. 이미 읽은 엔티티에 쿼리로 걸면 version이 오르지 않는다(실측).
     * 내용이 바뀌는 수정에는 쓰지 않는다. dirty UPDATE와 겹치면 version이 둘 오른다.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    fun findForVersionBumpByStoreId(storeId: UUID): MenuEntity?
}
