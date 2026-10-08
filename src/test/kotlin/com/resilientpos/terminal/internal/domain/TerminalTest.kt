package com.resilientpos.terminal.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalStatus
import java.util.UUID
import kotlin.test.Test
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val storeId = StoreId(UUID.randomUUID())
    private val code = TerminalCode("POS-1")
    private val ids = object : IdentifierGenerator {
        override fun next(): UUID = id
    }

    @Test
    fun `등록한 단말은 ACTIVE이고 매장과 식별자를 가진다`() {
        val terminal = Terminal.register(storeId, code, ids)

        assertEquals(TerminalId(id), terminal.id)
        assertEquals(code, terminal.terminalCode)
        assertEquals(storeId, terminal.storeId)
        assertEquals(TerminalStatus.ACTIVE, terminal.status)
    }

    @Test
    fun `다른 매장 ID를 확인하면 TERMINAL_NOT_FOUND이고 같은 매장이면 통과한다`() {
        val terminal = Terminal.register(storeId, code, ids)

        terminal.requireStore(storeId)
        val failure = assertFailsWith<DomainException> { terminal.requireStore(StoreId(UUID.randomUUID())) }

        assertEquals("TERMINAL_NOT_FOUND", failure.code)
        assertEquals(DomainErrorKind.NOT_FOUND, failure.kind)
    }

    @Test
    fun `비활성화는 새 인스턴스를 돌려주고 원본은 그대로다`() {
        val active = Terminal.register(storeId, code, ids)

        val inactive = active.deactivate()

        assertEquals(TerminalStatus.INACTIVE, inactive.status)
        assertEquals(TerminalStatus.ACTIVE, active.status)
        assertEquals(active.id, inactive.id)
        assertEquals(storeId, inactive.storeId)
    }

    @Test
    fun `이미 비활성인 단말의 비활성화는 같은 상태다`() {
        val inactive = Terminal.register(storeId, code, ids).deactivate()

        assertEquals(TerminalStatus.INACTIVE, inactive.deactivate().status)
    }

    @Test
    fun `활성 단말은 credential 발급 전제 조건을 통과한다`() {
        Terminal.register(storeId, code, ids).requireActive()
    }

    @Test
    fun `비활성 단말의 credential 발급 전제 조건은 TERMINAL_INACTIVE CONFLICT로 거절한다`() {
        val inactive = Terminal.register(storeId, code, ids).deactivate()

        val failure = assertFailsWith<DomainException> { inactive.requireActive() }

        assertEquals("TERMINAL_INACTIVE", failure.code)
        assertEquals(DomainErrorKind.CONFLICT, failure.kind)
        assertEquals(TerminalStatus.INACTIVE, inactive.status)
    }
}
