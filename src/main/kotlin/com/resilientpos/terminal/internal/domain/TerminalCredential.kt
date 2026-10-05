package com.resilientpos.terminal.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.terminal.TerminalId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * 단말의 인증 credential 한 건. 평문 토큰은 갖지 않고 SHA-256 hex만 가진다.
 * 폐기([revokedAt])된 행도 이력으로 남고, 유효 credential은 단말당 하나다. 만료는 없다.
 */
class TerminalCredential private constructor(
    val id: UUID,
    val terminalId: TerminalId,
    val tokenHash: String,
    val revokedAt: Instant?,
) {

    /** 폐기한다. 폐기된 것을 다시 폐기하는 경로는 없다(유효 credential만 읽어 부른다). */
    fun revoke(now: Instant): TerminalCredential = TerminalCredential(id, terminalId, tokenHash, now)

    /** 발급 결과. [token]은 평문이고 이 객체 밖에 남기지 않는다. */
    class Issued(val credential: TerminalCredential, val token: String)

    companion object {
        private val random = SecureRandom()

        /** 256bit 난수를 base64url(패딩 없음)로 표기한 토큰과 그 해시를 가진 credential을 만든다. */
        fun issue(terminalId: TerminalId, ids: IdentifierGenerator): Issued {
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            return Issued(TerminalCredential(ids.next(), terminalId, hash(token), null), token)
        }

        /** 토큰의 SHA-256 소문자 hex. 엔트로피가 충분해 salt도 느린 해시도 쓰지 않는다. */
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).let(HexFormat.of()::formatHex)

        /** 저장된 credential을 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(id: UUID, terminalId: TerminalId, tokenHash: String, revokedAt: Instant?) =
            TerminalCredential(id, terminalId, tokenHash, revokedAt)
    }
}
