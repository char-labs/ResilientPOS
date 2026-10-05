package com.resilientpos.terminal.internal.application

import com.resilientpos.PostgresIntegrationTest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/** V012의 두 unique(해시, 단말당 유효 credential 하나)를 서비스를 거치지 않고 직접 INSERT로 확인한다. Docker가 필요하다. */
class TerminalCredentialSchemaIntegrationTest(@Autowired private val jdbcTemplate: JdbcTemplate) : PostgresIntegrationTest() {

    private val now = Timestamp.from(Instant.parse("2026-10-05T01:00:00Z"))

    private fun insert(terminalId: UUID, tokenHash: String, revoked: Boolean) {
        jdbcTemplate.update(
            "insert into t_terminal_credential (id, terminal_id, token_hash, revoked_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), terminalId, tokenHash, if (revoked) now else null, now, now,
        )
    }

    private fun hash() = UUID.randomUUID().toString().replace("-", "").repeat(2)

    @Test
    fun `같은 token_hash 행 두 개는 unique 위반이다`() {
        val duplicated = hash()
        insert(UUID.randomUUID(), duplicated, revoked = false)

        val failure = assertFailsWith<DuplicateKeyException> { insert(UUID.randomUUID(), duplicated, revoked = false) }

        assertEquals(true, failure.message!!.contains("ux_terminal_credential_token_hash"), failure.message)
    }

    @Test
    fun `같은 단말의 revoked_at이 null인 행 두 개는 unique 위반이다`() {
        val terminalId = UUID.randomUUID()
        insert(terminalId, hash(), revoked = false)

        val failure = assertFailsWith<DuplicateKeyException> { insert(terminalId, hash(), revoked = false) }

        assertEquals(true, failure.message!!.contains("ux_terminal_credential_active"), failure.message)
    }

    @Test
    fun `폐기된 행은 같은 단말에 여러 개 넣을 수 있고 유효 행 하나와도 함께 둘 수 있다`() {
        val terminalId = UUID.randomUUID()

        repeat(3) { insert(terminalId, hash(), revoked = true) }
        insert(terminalId, hash(), revoked = false)

        assertEquals(4L, jdbcTemplate.queryForObject<Long>("select count(*) from t_terminal_credential where terminal_id = ?", terminalId))
    }
}
