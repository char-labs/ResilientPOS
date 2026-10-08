package com.resilientpos.terminal

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalCodeTest {

    @Test
    fun `영대문자 숫자 하이픈 1자와 32자는 받는다`() {
        assertEquals("A", TerminalCode("A").value)
        assertEquals("A".repeat(32), TerminalCode("A".repeat(32)).value)
        assertEquals("POS-01-9", TerminalCode("POS-01-9").value)
    }

    @Test
    fun `빈 문자열 33자 소문자 허용 밖 문자는 INVALID_TERMINAL_CODE로 거절한다`() {
        listOf("", "A".repeat(33), "pos-1", "Pos-1", "POS 1", "POS_1", "포스1", "POS-1\n").forEach {
            val failure = assertFailsWith<DomainException>(it) { TerminalCode(it) }

            assertEquals("INVALID_TERMINAL_CODE", failure.code, it)
            assertEquals(DomainErrorKind.INVALID, failure.kind, it)
        }
    }
}
