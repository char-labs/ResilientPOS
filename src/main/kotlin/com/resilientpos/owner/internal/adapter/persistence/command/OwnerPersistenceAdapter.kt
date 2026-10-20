package com.resilientpos.owner.internal.adapter.persistence.command

import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.owner.internal.domain.OwnerRepository
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.selectFrom
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `OwnerRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 * 새 행은 `EntityManager.persist`로 넣는다. `@Version`이 없어 `save`가 `merge`로 가면 SELECT가 한 번 더 나간다.
 */
@Component
class OwnerPersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : OwnerRepository {

    @Transactional
    override fun save(owner: Owner): Owner {
        // 같은 이메일은 unique가 막고 위반은 커밋에서 드러나 `OwnerManagement.signUp`이 번역한다.
        entityManager.persist(OwnerEntity.from(owner))
        return owner
    }

    override fun findByEmail(email: OwnerEmail): Owner? =
        queries.findFirst {
            selectFrom(OwnerEntity::class).where(path(OwnerEntity::email).eq(email.value))
        }?.toDomain()
}
