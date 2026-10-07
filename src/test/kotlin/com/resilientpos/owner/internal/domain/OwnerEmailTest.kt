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
    fun `normalize는 검증 없이 같은 정규화만 한다`() {
        assertEquals("not an email", OwnerEmail.normalize(" Not An Email "))
    }
}
