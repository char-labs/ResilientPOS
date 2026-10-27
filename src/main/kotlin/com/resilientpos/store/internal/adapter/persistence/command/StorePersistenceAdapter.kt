package com.resilientpos.store.internal.adapter.persistence.command

import com.resilientpos.store.internal.domain.Store
import com.resilientpos.store.internal.domain.StoreRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** `StoreRepository`의 JPA 구현. 새 행은 [StoreJpaRepository.save]로 넣는다(`@Version`이 없어 `merge`로 가 SELECT가 하나 더 나간다). */
@Component
class StorePersistenceAdapter(private val jpaRepository: StoreJpaRepository) : StoreRepository {

    @Transactional
    override fun save(store: Store): Store {
        jpaRepository.save(StoreEntity.from(store))
        return store
    }
}
