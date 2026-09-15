package com.resilientpos.foundation

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7IdentifierGeneratorTest {

    private val generator: IdentifierGenerator = UuidV7IdentifierGenerator()

    @Test
    fun `version 7 식별자를 만든다`() {
        assertEquals(7, generator.next().version())
    }

    @Test
    fun `연속 생성한 식별자의 시간 성분이 뒤로 가지 않는다`() {
        val timestamps = (1..1_000).map { generator.next().epochMillis() }

        assertEquals(timestamps.sorted(), timestamps)
    }

    @Test
    fun `연속 생성한 식별자에 중복이 없다`() {
        val identifiers = (1..10_000).map { generator.next() }

        assertEquals(identifiers.size, identifiers.toSet().size)
    }

    @Test
    fun `시간 성분은 생성 시각을 담는다`() {
        val before = System.currentTimeMillis()
        val generated = generator.next().epochMillis()

        assertTrue(generated in before..System.currentTimeMillis(), "생성 시각 범위 밖: $generated")
    }

    private fun UUID.epochMillis(): Long = mostSignificantBits ushr 16
}
