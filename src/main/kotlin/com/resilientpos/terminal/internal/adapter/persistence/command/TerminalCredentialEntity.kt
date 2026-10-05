package com.resilientpos.terminal.internal.adapter.persistence.command

import com.resilientpos.platform.BaseEntity
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.internal.domain.TerminalCredential
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 역할: 단말의 인증 credential 한 건. 평문 토큰이 아니라 SHA-256 hex만 저장하고, 폐기해도 행은 남는 이력이다.
 * 관계: `terminalId`로 [TerminalEntity]를 가리킨다(FK 없음). 유효 행은 단말당 하나이고 partial unique가 지킨다.
 * 흐름: 등록·회전이 새 행을 넣고, 회전이 옛 행에 `revokedAt`을 찍는다. 인증은 해시로 이 행을 찾는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `TerminalCredential`의 JPA 매핑. 바뀌는 것은 `revokedAt`뿐이다. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 */
@Entity
@Table(name = "t_terminal_credential")
class TerminalCredentialEntity private constructor(
    id: UUID,
    terminalId: UUID,
    tokenHash: String,
    revokedAt: Instant?,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var terminalId: UUID = terminalId
        protected set

    var tokenHash: String = tokenHash
        protected set

    var revokedAt: Instant? = revokedAt
        protected set

    fun applyRevokedAt(revokedAt: Instant?) {
        this.revokedAt = revokedAt
    }

    fun toDomain(): TerminalCredential = TerminalCredential.restore(id, TerminalId(terminalId), tokenHash, revokedAt)

    companion object {
        fun from(credential: TerminalCredential): TerminalCredentialEntity =
            TerminalCredentialEntity(credential.id, credential.terminalId.value, credential.tokenHash, credential.revokedAt)
    }
}
