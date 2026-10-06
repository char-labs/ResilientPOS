package com.resilientpos.terminal.internal.adapter.persistence.command

import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.selectFrom
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.internal.domain.Terminal
import com.resilientpos.terminal.internal.domain.TerminalCredential
import com.resilientpos.terminal.internal.domain.TerminalRepository
import com.resilientpos.terminal.TerminalStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `TerminalRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 * 새 행은 `EntityManager.persist`로 넣는다. 할당식 `@Id`에 `@Version`이 없어 `save`가 `merge`로 가면 SELECT가 한 번 더 나간다.
 * `deleted_at is null`은 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
 */
@Component
class TerminalPersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : TerminalRepository {

    override fun findActiveByTokenHash(tokenHash: String): Terminal? =
        queries.findFirst {
            val terminal = entity(TerminalEntity::class)
            val credential = entity(TerminalCredentialEntity::class)
            select(terminal)
                .from(terminal, credential)
                .where(
                    and(
                        credential.path(TerminalCredentialEntity::terminalId).eq(terminal.path(TerminalEntity::id)),
                        credential.path(TerminalCredentialEntity::tokenHash).eq(tokenHash),
                        credential.path(TerminalCredentialEntity::revokedAt).isNull(),
                        terminal.path(TerminalEntity::status).eq(TerminalStatus.ACTIVE),
                    ),
                )
        }?.toDomain()

    override fun findByIdForUpdate(terminalId: TerminalId): Terminal? =
        queries.findFirstForUpdate {
            selectFrom(TerminalEntity::class).where(path(TerminalEntity::id).eq(terminalId.value))
        }?.toDomain()

    override fun findActiveCredential(terminalId: TerminalId): TerminalCredential? =
        queries.findFirst {
            selectFrom(TerminalCredentialEntity::class).where(
                and(
                    path(TerminalCredentialEntity::terminalId).eq(terminalId.value),
                    path(TerminalCredentialEntity::revokedAt).isNull(),
                ),
            )
        }?.toDomain()

    @Transactional
    override fun save(terminal: Terminal): Terminal {
        entityManager.persist(TerminalEntity.from(terminal))
        try {
            entityManager.flush()
        } catch (e: PersistenceException) {
            // 사전 조회 없이 unique가 막는다. 다른 제약 위반은 그대로 던진다. 예외 뒤 영속성 컨텍스트는 쓰지 않고 트랜잭션은 롤백된다.
            if ((e as? ConstraintViolationException)?.constraintName == TERMINAL_CODE_CONSTRAINT) throw Terminal.codeDuplicated()
            throw e
        }
        return terminal
    }

    @Transactional
    override fun save(credential: TerminalCredential): TerminalCredential {
        entityManager.persist(TerminalCredentialEntity.from(credential))
        return credential
    }

    /** 방금 잠그고 읽은 행이라 영속성 컨텍스트에 있다. 없으면 불변식이 깨진 것이라 도메인 예외로 바꾸지 않는다. */
    @Transactional
    override fun update(terminal: Terminal): Terminal {
        val entity = entityManager.find(TerminalEntity::class.java, terminal.id.value)
            ?: error("갱신하려는 단말이 없다: ${terminal.id.value}")
        entity.applyStatus(terminal.status)
        return terminal
    }

    /** Hibernate는 한 flush에서 UPDATE보다 INSERT를 먼저 보낸다. 새 유효 행의 INSERT가 옛 행의 폐기보다 앞서면 partial unique에 걸리므로 먼저 내보낸다. */
    @Transactional
    override fun update(credential: TerminalCredential): TerminalCredential {
        val entity = entityManager.find(TerminalCredentialEntity::class.java, credential.id)
            ?: error("갱신하려는 credential이 없다: ${credential.id}")
        entity.applyRevokedAt(credential.revokedAt)
        entityManager.flush()
        return credential
    }

    private companion object {
        const val TERMINAL_CODE_CONSTRAINT = "ux_terminal_store_code"
    }
}
