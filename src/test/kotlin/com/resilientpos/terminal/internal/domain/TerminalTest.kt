package com.resilientpos.terminal.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val storeId = StoreId(UUID.randomUUID())
    private val ids = object : IdentifierGenerator {
        override fun next(): UUID = id
    }

    @Test
    fun `등록한 단말은 ACTIVE이고 매장과 식별자를 가진다`() {
        val terminal = Terminal.register(storeId, ids)

        assertEquals(TerminalId(id), terminal.id)
        assertEquals(storeId, terminal.storeId)
        assertEquals(TerminalStatus.ACTIVE, terminal.status)
    }

    @Test
    fun `비활성화는 새 인스턴스를 돌려주고 원본은 그대로다`() {
        val active = Terminal.register(storeId, ids)

        val inactive = active.deactivate()

        assertEquals(TerminalStatus.INACTIVE, inactive.status)
        assertEquals(TerminalStatus.ACTIVE, active.status)
        assertEquals(active.id, inactive.id)
        assertEquals(storeId, inactive.storeId)
    }

    @Test
    fun `이미 비활성인 단말의 비활성화는 같은 상태다`() {
        val inactive = Terminal.register(storeId, ids).deactivate()

        assertEquals(TerminalStatus.INACTIVE, inactive.deactivate().status)
    }
}
