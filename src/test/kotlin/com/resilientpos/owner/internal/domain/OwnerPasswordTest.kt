package com.resilientpos.owner.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerPasswordTest {

    @Test
    fun `15자부터 받고 14자는 거절한다`() {
        OwnerPassword.requireValid("a".repeat(15))

        val e = assertFailsWith<DomainException> { OwnerPassword.requireValid("a".repeat(14)) }
        assertEquals(Owner.INVALID_PASSWORD, e.code)
        assertEquals(DomainErrorKind.INVALID, e.kind)
    }

    @Test
    fun `길이는 UTF-16 단위가 아니라 code point로 센다`() {
        assertTrue(OwnerPassword.isValid("😀".repeat(15)))
        assertFalse(OwnerPassword.isValid("😀".repeat(14)))
    }

    @Test
    fun `UTF-8 72바이트까지 받고 73바이트는 거절한다`() {
        assertTrue(OwnerPassword.isValid("a".repeat(72)))
        assertFalse(OwnerPassword.isValid("a".repeat(73)))
        // 한글 24자 = 72바이트, 25자 = 75바이트
        assertTrue(OwnerPassword.isValid("가".repeat(24)))
        assertFalse(OwnerPassword.isValid("가".repeat(25)))
    }

    @Test
    fun `조합 규칙은 없다`() {
        assertTrue(OwnerPassword.isValid("aaaaaaaaaaaaaaa"))
    }
}
