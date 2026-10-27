package com.resilientpos.catalog.internal.adapter.persistence.command

import com.resilientpos.catalog.internal.adapter.persistence.MenuDocumentMapper
import com.resilientpos.catalog.internal.domain.Menu
import com.resilientpos.catalog.internal.domain.MenuRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `MenuRepository`의 JPA 구현. 새 행은 [MenuJpaRepository.save]로 넣는다. `MenuEntity`는 `Long?` `@Version`이라 Spring Data가
 * 새 엔티티를 `persist`로 보낸다. `merge`로 가면 생성 경합의 늦은 쪽이 먼저 커밋된 행을 읽고 덮어쓴다.
 */
@Component
class MenuPersistenceAdapter(
    private val menus: MenuJpaRepository,
    private val documents: MenuDocumentMapper,
) : MenuRepository {

    @Transactional
    override fun create(menu: Menu): Long {
        val saved = menus.save(MenuEntity.from(menu.storeId.value, documents.toJson(menu)))
        return checkNotNull(saved.version) { "persist가 version을 채운다" }
    }

    /**
     * 내용이 바뀌면 dirty UPDATE가 version을 올리고, 같으면 `OPTIMISTIC_FORCE_INCREMENT`로 올린다. 둘은 한 번만 오른다.
     * 처음 읽을 때부터 `OPTIMISTIC_FORCE_INCREMENT`를 주면 dirty 변경과 겹칠 때 version이 둘 오른다(실측: 한 번 수정에 0에서 2).
     * 이미 읽은 엔티티에 잠금을 나중에 걸면 version이 오르지 않는다(실측). 그래서 내용이 같은지는 엔티티를 올리지 않는 투영으로 먼저 가리고,
     * 엔티티는 그 결과에 맞는 방식으로 한 번만 읽는다. jsonb가 공백을 정규화하므로 문자열 비교가 아니다. 두 읽기 사이에 다른 트랜잭션이
     * 커밋하면 읽은 엔티티의 version이 달라져 null이다.
     */
    @Transactional
    override fun replace(menu: Menu, expectedVersion: Long): Long? {
        val storeId = menu.storeId.value
        val snapshot = menus.findSnapshotByStoreId(storeId) ?: return null
        if (snapshot.version != expectedVersion) return null
        val json = documents.toJson(menu)
        val isSame = documents.isSame(snapshot.menu, json)
        val entity = (if (isSame) menus.findForVersionBumpByStoreId(storeId) else menus.findByIdOrNull(storeId)) ?: return null
        if (entity.version != expectedVersion) return null
        if (!isSame) entity.apply(json)
        return expectedVersion + 1
    }
}
