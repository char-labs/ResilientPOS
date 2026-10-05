package com.resilientpos.terminal.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.platform.Tx
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalCommand
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

class TerminalManagementIntegrationTest(
    @Autowired private val terminals: TerminalManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private val storeId = StoreId(UUID.randomUUID())

    private fun register() = terminals.register(TerminalCommand.Register(storeId))

    private fun count(sql: String, vararg args: Any): Long = jdbcTemplate.queryForObject<Long>(sql, *args)!!

    private fun activeCredentials(terminalId: TerminalId) = count(
        "select count(*) from t_terminal_credential where terminal_id = ? and revoked_at is null", terminalId.value,
    )

    private fun sha256Hex(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `등록하면 ACTIVE 단말과 credential 하나가 생기고 DB에는 토큰의 해시만 있다`() {
        val registered = register()

        assertEquals(
            "ACTIVE",
            jdbcTemplate.queryForObject<String>("select status from t_terminal where id = ?", registered.terminalId.value),
        )
        assertEquals(
            storeId.value,
            jdbcTemplate.queryForObject<UUID>("select store_id from t_terminal where id = ?", registered.terminalId.value),
        )
        assertEquals(
            listOf(sha256Hex(registered.token)),
            jdbcTemplate.queryForList(
                "select token_hash from t_terminal_credential where terminal_id = ?", String::class.java, registered.terminalId.value,
            ),
        )
        assertEquals(0L, count("select count(*) from t_terminal_credential where token_hash = ?", registered.token))
    }

    @Test
    fun `유효한 토큰은 단말과 매장을 돌려주고 모르는 토큰은 없음이다`() {
        val registered = register()

        assertEquals(AuthenticatedTerminal(registered.terminalId, storeId), terminals.authenticate(registered.token))
        assertNull(terminals.authenticate("unknown-token"))
    }

    @Test
    fun `회전하면 새 토큰만 인증되고 옛 credential 행은 폐기된 채 남는다`() {
        val registered = register()

        val rotated = terminals.rotateCredential(TerminalCommand.RotateCredential(registered.terminalId))

        assertNull(terminals.authenticate(registered.token))
        assertEquals(AuthenticatedTerminal(registered.terminalId, storeId), terminals.authenticate(rotated.token))
        assertEquals(
            1L,
            count(
                "select count(*) from t_terminal_credential where token_hash = ? and revoked_at is not null",
                sha256Hex(registered.token),
            ),
        )
        assertEquals(2L, count("select count(*) from t_terminal_credential where terminal_id = ?", registered.terminalId.value))
        assertEquals(1L, activeCredentials(registered.terminalId))
    }

    @Test
    fun `같은 단말의 회전이 겹쳐도 유효 credential은 하나다`() {
        val registered = register()
        val executor = Executors.newFixedThreadPool(2)

        val tokens = try {
            List(2) { executor.submit<String> { terminals.rotateCredential(TerminalCommand.RotateCredential(registered.terminalId)).token } }
                .map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1L, activeCredentials(registered.terminalId))
        assertEquals(3L, count("select count(*) from t_terminal_credential where terminal_id = ?", registered.terminalId.value))
        assertNull(terminals.authenticate(registered.token))
        assertEquals(1, tokens.count { terminals.authenticate(it) != null })
    }

    @Test
    fun `단말 행이 잠겨 있으면 회전은 잠금이 풀리기를 기다린다`() {
        val registered = register()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val late = Tx.masterTx {
                jdbcTemplate.queryForObject<UUID>("select id from t_terminal where id = ? for update", registered.terminalId.value)
                val late = executor.submit<TerminalCommand.RotateCredential.Result> {
                    terminals.rotateCredential(TerminalCommand.RotateCredential(registered.terminalId))
                }
                runCatching { late.get(500, TimeUnit.MILLISECONDS) }
                assertFalse(late.isDone, "회전이 단말 행 잠금을 기다리지 않았다")
                late
            }

            val rotated = late.get(5, TimeUnit.SECONDS)
            assertEquals(AuthenticatedTerminal(registered.terminalId, storeId), terminals.authenticate(rotated.token))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `비활성화는 멱등이고 그 단말의 토큰은 인증되지 않는다`() {
        val registered = register()

        terminals.deactivate(TerminalCommand.Deactivate(registered.terminalId))
        terminals.deactivate(TerminalCommand.Deactivate(registered.terminalId))

        assertEquals(
            "INACTIVE",
            jdbcTemplate.queryForObject<String>("select status from t_terminal where id = ?", registered.terminalId.value),
        )
        assertNull(terminals.authenticate(registered.token))
    }

    @Test
    fun `비활성 단말의 회전은 TERMINAL_INACTIVE이고 credential이 늘지 않는다`() {
        val registered = register()
        terminals.deactivate(TerminalCommand.Deactivate(registered.terminalId))

        val failure = assertFailsWith<DomainException> {
            terminals.rotateCredential(TerminalCommand.RotateCredential(registered.terminalId))
        }

        assertEquals("TERMINAL_INACTIVE", failure.code)
        assertEquals(DomainErrorKind.CONFLICT, failure.kind)
        assertEquals(1L, count("select count(*) from t_terminal_credential where terminal_id = ?", registered.terminalId.value))
    }

    @Test
    fun `비활성화가 먼저 커밋되면 잠금을 기다리던 회전은 TERMINAL_INACTIVE이고 credential이 늘지 않는다`() {
        val registered = register()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val rotation = Tx.masterTx {
                jdbcTemplate.queryForObject<UUID>("select id from t_terminal where id = ? for update", registered.terminalId.value)
                val rotation = executor.submit<TerminalCommand.RotateCredential.Result> {
                    terminals.rotateCredential(TerminalCommand.RotateCredential(registered.terminalId))
                }
                runCatching { rotation.get(500, TimeUnit.MILLISECONDS) }
                assertFalse(rotation.isDone, "회전이 단말 행 잠금을 기다리지 않았다")
                jdbcTemplate.update("update t_terminal set status = 'INACTIVE' where id = ?", registered.terminalId.value)
                rotation
            }

            val failure = assertFailsWith<ExecutionException> { rotation.get(5, TimeUnit.SECONDS) }

            val cause = failure.cause as DomainException
            assertEquals("TERMINAL_INACTIVE", cause.code)
            assertEquals(1L, count("select count(*) from t_terminal_credential where terminal_id = ?", registered.terminalId.value))
            assertNull(terminals.authenticate(registered.token))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `모르는 단말의 회전과 비활성화는 TERMINAL_NOT_FOUND다`() {
        val unknown = TerminalId(UUID.randomUUID())

        val rotate = assertFailsWith<DomainException> { terminals.rotateCredential(TerminalCommand.RotateCredential(unknown)) }
        val deactivate = assertFailsWith<DomainException> { terminals.deactivate(TerminalCommand.Deactivate(unknown)) }

        listOf(rotate, deactivate).forEach {
            assertEquals("TERMINAL_NOT_FOUND", it.code)
            assertEquals(DomainErrorKind.NOT_FOUND, it.kind)
        }
    }
}
