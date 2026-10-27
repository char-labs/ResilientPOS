package com.resilientpos.catalog.internal.adapter.persistence.command

import com.resilientpos.catalog.internal.adapter.persistence.MenuDocumentMapper
import com.resilientpos.catalog.internal.domain.Menu
import com.resilientpos.catalog.internal.domain.MenuRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `MenuRepository`의 JPA 구현. 새 행은 `persist`로 넣는다(version이 0으로 시작해 `save`가 `merge`로 가면
 * 생성 경합의 늦은 쪽이 먼저 커밋된 행을 읽고 덮어쓴다).
 */
@Component
class MenuPersistenceAdapter(
    private val entityManager: EntityManager,
    private val documents: MenuDocumentMapper,
) : MenuRepository {

    @Transactional
    override fun create(menu: Menu): Long {
        val entity = MenuEntity.from(menu.storeId.value, documents.toJson(menu))
        entityManager.persist(entity)
        return checkNotNull(entity.version) { "persist가 version을 채운다" }
    }

    /**
     * 내용이 바뀌면 dirty UPDATE가 version을 올리고, 같으면 `OPTIMISTIC_FORCE_INCREMENT`로 올린다. 둘은 한 번만 오른다.
     * `find`에 `OPTIMISTIC_FORCE_INCREMENT`를 주면 dirty 변경과 겹칠 때 version이 둘 오른다(실측: 한 번 수정에 0에서 2).
     * 그래서 내용이 같은지는 JSON 값으로 가려 같을 때만 강제로 올린다. jsonb가 공백을 정규화하므로 문자열 비교가 아니다.
     */
    @Transactional
    override fun replace(menu: Menu, expectedVersion: Long): Long? {
        val entity = entityManager.find(MenuEntity::class.java, menu.storeId.value) ?: return null
        if (entity.version != expectedVersion) return null
        val json = documents.toJson(menu)
        if (documents.isSame(entity.menu, json)) {
            entityManager.lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
        } else {
            entity.apply(json)
        }
        return expectedVersion + 1
    }
}
