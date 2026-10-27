package com.resilientpos.terminal.internal.adapter.persistence.command

import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.selectFrom
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalStatus
import com.resilientpos.terminal.internal.domain.Terminal
import com.resilientpos.terminal.internal.domain.TerminalCredential
import com.resilientpos.terminal.internal.domain.TerminalRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `TerminalRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 * 새 행은 Spring Data의 `save`로 넣는다. 할당식 `@Id`에 `@Version`이 없어 `merge`로 가 SELECT가 하나 더 나간다.
 * `deleted_at is null`은 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
 */
@Component
class TerminalPersistenceAdapter(
    private val terminals: TerminalJpaRepository,
    private val credentials: TerminalCredentialJpaRepository,
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
        // 같은 매장·코드는 unique가 막고 위반은 커밋에서 드러나 `TerminalManagement.register`가 번역한다.
        terminals.save(TerminalEntity.from(terminal))
        return terminal
    }

    @Transactional
    override fun save(credential: TerminalCredential): TerminalCredential {
        credentials.save(TerminalCredentialEntity.from(credential))
        return credential
    }

    /** 방금 잠그고 읽은 행이라 영속성 컨텍스트에 있다. 없으면 불변식이 깨진 것이라 도메인 예외로 바꾸지 않는다. */
    @Transactional
    override fun update(terminal: Terminal): Terminal {
        val entity = terminals.findByIdOrNull(terminal.id.value)
            ?: error("갱신하려는 단말이 없다: ${terminal.id.value}")
        entity.applyStatus(terminal.status)
        return terminal
    }

    /** 폐기와 새 발급은 같은 커밋에 들어가고 `ux_terminal_credential_active`는 커밋 때 검사한다. */
    @Transactional
    override fun update(credential: TerminalCredential): TerminalCredential {
        val entity = credentials.findByIdOrNull(credential.id)
            ?: error("갱신하려는 credential이 없다: ${credential.id}")
        entity.applyRevokedAt(credential.revokedAt)
        return credential
    }
}
