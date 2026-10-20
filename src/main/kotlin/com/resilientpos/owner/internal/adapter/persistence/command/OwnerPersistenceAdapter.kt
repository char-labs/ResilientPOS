package com.resilientpos.owner.internal.adapter.persistence.command

import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.owner.internal.domain.OwnerRepository
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.isUniqueViolationOf
import com.resilientpos.platform.selectFrom
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
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
        entityManager.persist(OwnerEntity.from(owner))
        try {
            entityManager.flush()
        } catch (e: PersistenceException) {
            // 사전 조회 없이 unique가 막는다. 다른 제약 위반은 그대로 던진다. 예외 뒤 영속성 컨텍스트는 쓰지 않고 트랜잭션은 롤백된다.
            // SQLState와, 어느 언어의 메시지에도 그대로 실리는 제약 이름으로 가린다(서버 lc_messages에 기대지 않는다).
            if (e.isUniqueViolationOf(OWNER_EMAIL_CONSTRAINT)) throw Owner.emailDuplicated()
            throw e
        }
        return owner
    }

    override fun findByEmail(email: OwnerEmail): Owner? =
        queries.findFirst {
            selectFrom(OwnerEntity::class).where(path(OwnerEntity::email).eq(email.value))
        }?.toDomain()

    private companion object {
        const val OWNER_EMAIL_CONSTRAINT = "ux_owner_email"
    }
}
