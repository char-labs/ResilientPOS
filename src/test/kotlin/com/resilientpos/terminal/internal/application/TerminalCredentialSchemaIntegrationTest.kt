package com.resilientpos.terminal.internal.application

import com.resilientpos.PostgresIntegrationTest
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionTemplate

/**
 * V012의 unique(해시)와 V016의 exclusion 제약(단말당 유효 credential 하나, 커밋 때 검사)을 서비스를 거치지 않고 직접 SQL로 확인한다. Docker가 필요하다.
 */
class TerminalCredentialSchemaIntegrationTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactionTemplate: TransactionTemplate,
) : PostgresIntegrationTest() {

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
    fun `같은 단말의 revoked_at이 null인 행 두 개는 exclusion 위반이다`() {
        val terminalId = UUID.randomUUID()
        insert(terminalId, hash(), revoked = false)

        val failure = assertFailsWith<DataIntegrityViolationException> { insert(terminalId, hash(), revoked = false) }

        assertEquals("23P01", (failure.mostSpecificCause as? SQLException)?.sqlState, "unique(23505)가 아니라 exclusion 위반이다")
        assertEquals(true, failure.message.orEmpty().contains("ux_terminal_credential_active"), failure.message)
    }

    @Test
    fun `한 트랜잭션에서 새 유효 행을 넣은 뒤 옛 행을 폐기하면 커밋되고 유효 행은 하나다`() {
        val terminalId = UUID.randomUUID()
        val oldHash = hash()
        insert(terminalId, oldHash, revoked = false)

        transactionTemplate.executeWithoutResult {
            insert(terminalId, hash(), revoked = false)
            jdbcTemplate.update("update t_terminal_credential set revoked_at = ? where token_hash = ?", now, oldHash)
        }

        assertEquals(1L, jdbcTemplate.queryForObject<Long>("select count(*) from t_terminal_credential where terminal_id = ? and revoked_at is null", terminalId))
    }

    @Test
    fun `한 트랜잭션에서 같은 단말의 유효 행 둘을 넣으면 커밋에서 실패한다`() {
        val terminalId = UUID.randomUUID()
        var secondInsertSucceeded = false

        val failure = assertFailsWith<Exception> {
            transactionTemplate.executeWithoutResult {
                insert(terminalId, hash(), revoked = false)
                insert(terminalId, hash(), revoked = false)
                secondInsertSucceeded = true
            }
        }

        assertEquals(true, secondInsertSucceeded, "두 번째 INSERT는 성공하고 커밋에서 실패해야 한다")
        assertEquals(true, generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("ux_terminal_credential_active") == true }, failure.toString())
        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_terminal_credential where terminal_id = ?", terminalId))
    }

    @Test
    fun `폐기된 행은 같은 단말에 여러 개 넣을 수 있고 유효 행 하나와도 함께 둘 수 있다`() {
        val terminalId = UUID.randomUUID()

        repeat(3) { insert(terminalId, hash(), revoked = true) }
        insert(terminalId, hash(), revoked = false)

        assertEquals(4L, jdbcTemplate.queryForObject<Long>("select count(*) from t_terminal_credential where terminal_id = ?", terminalId))
    }
}
