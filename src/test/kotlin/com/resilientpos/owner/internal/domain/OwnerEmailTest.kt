package com.resilientpos.owner.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OwnerEmailTest {

    @Test
    fun `앞뒤 공백을 지우고 소문자로 바꾼다`() {
        assertEquals("owner@example.com", OwnerEmail.of("  Owner@Example.COM \t").value)
    }

    @Test
    fun `254자는 받고 255자는 거절한다`() {
        val local = "a".repeat(254 - "@x.io".length)

        assertEquals(254, OwnerEmail.of("$local@x.io").value.length)
        val e = assertFailsWith<DomainException> { OwnerEmail.of("a$local@x.io") }
        assertEquals(Owner.INVALID_EMAIL, e.code)
        assertEquals(DomainErrorKind.INVALID, e.kind)
    }

    @Test
    fun `골뱅이 앞뒤에 공백 없는 문자가 없으면 거절한다`() {
        listOf("", "plain", "@example.com", "owner@", "ow ner@example.com", "owner@exam ple.com").forEach {
            val e = assertFailsWith<DomainException>(it) { OwnerEmail.of(it) }
            assertEquals(Owner.INVALID_EMAIL, e.code, it)
        }
    }

    @Test
    fun `제어 문자와 서식 문자가 있으면 INVALID_EMAIL이다`() {
        listOf("ow\u0000ner@example.com", "owner@example.com\u0000", "ow\u200Bner@example.com", "owner@exam\u200Dple.com", "ow\u0080ner@example.com", "ow\u007Fner@example.com")
            .forEach {
                val e = assertFailsWith<DomainException>(it.toByteArray().toList().toString()) { OwnerEmail.of(it) }
                assertEquals(Owner.INVALID_EMAIL, e.code)
                assertEquals(DomainErrorKind.INVALID, e.kind)
            }
    }

    @Test
    fun `일반 유니코드 문자는 받는다`() {
        assertEquals("소유자@example.com", OwnerEmail.of("소유자@Example.com").value)
    }
}
