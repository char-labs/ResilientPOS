package com.resilientpos.store.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoreTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val ownerId = OwnerId(UUID.randomUUID())
    private val ids = object : IdentifierGenerator {
        override fun next(): UUID = id
    }

    @Test
    fun `이름은 trim하고 식별자와 점주를 가진다`() {
        val store = Store.create(ownerId, "  강남점  ", ids)

        assertEquals(StoreId(id), store.id)
        assertEquals(ownerId, store.ownerId)
        assertEquals("강남점", store.name)
    }

    @Test
    fun `1자와 100자는 통과하고 100자는 code point로 센다`() {
        assertEquals("a", Store.create(ownerId, "a", ids).name)
        assertEquals(100, Store.create(ownerId, "a".repeat(100), ids).name.length)
        val emoji = "🍺".repeat(100)
        assertEquals(emoji, Store.create(ownerId, emoji, ids).name)
    }

    @Test
    fun `빈 이름 공백만 있는 이름 101자는 INVALID_STORE_NAME이다`() {
        listOf("", "   ", "\t\n", "a".repeat(101), "🍺".repeat(101), " " + "a".repeat(101) + " ").forEach { name ->
            val e = assertFailsWith<DomainException>(name) { Store.create(ownerId, name, ids) }
            assertEquals(Store.INVALID_STORE_NAME, e.code)
            assertEquals(DomainErrorKind.INVALID, e.kind)
        }
    }

    @Test
    fun `앞뒤 공백을 뗀 뒤 100자이면 통과한다`() {
        assertEquals(100, Store.create(ownerId, " " + "a".repeat(100) + " ", ids).name.length)
    }
}
