package com.resilientpos.terminal.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.terminal.TerminalId
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalCredentialTest {

    private val terminalId = TerminalId(UUID.randomUUID())
    private val ids = object : IdentifierGenerator {
        override fun next(): UUID = UUID.randomUUID()
    }

    @Test
    fun `토큰은 32바이트 난수의 base64url이고 해시는 토큰의 SHA-256 hex다`() {
        val issued = TerminalCredential.issue(terminalId, ids)

        assertEquals(43, issued.token.length)
        assertTrue(issued.token.matches(Regex("[A-Za-z0-9_-]+")), issued.token)
        assertEquals(32, Base64.getUrlDecoder().decode(issued.token).size)
        val expected = MessageDigest.getInstance("SHA-256").digest(issued.token.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, issued.credential.tokenHash)
        assertEquals(expected, TerminalCredential.hash(issued.token))
        assertEquals(terminalId, issued.credential.terminalId)
        assertNull(issued.credential.revokedAt)
    }

    @Test
    fun `발급마다 다른 토큰이다`() {
        assertNotEquals(TerminalCredential.issue(terminalId, ids).token, TerminalCredential.issue(terminalId, ids).token)
    }

    @Test
    fun `폐기는 revokedAt을 찍은 새 인스턴스를 돌려주고 원본은 그대로다`() {
        val credential = TerminalCredential.issue(terminalId, ids).credential
        val now = Instant.parse("2026-10-05T01:00:00Z")

        val revoked = credential.revoke(now)

        assertEquals(now, revoked.revokedAt)
        assertNull(credential.revokedAt)
        assertEquals(credential.id, revoked.id)
        assertEquals(credential.tokenHash, revoked.tokenHash)
        assertNotNull(revoked.revokedAt)
    }
}
