package com.resilientpos.store.internal.adapter.persistence.command

import com.resilientpos.store.internal.domain.Store
import com.resilientpos.store.internal.domain.StoreRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** `StoreRepository`의 JPA 구현. 새 행은 `persist`로 넣는다(`@Version`이 없어 `save`가 `merge`로 가면 SELECT가 한 번 더 나간다). */
@Component
class StorePersistenceAdapter(private val entityManager: EntityManager) : StoreRepository {

    @Transactional
    override fun save(store: Store): Store {
        entityManager.persist(StoreEntity.from(store))
        return store
    }
}
