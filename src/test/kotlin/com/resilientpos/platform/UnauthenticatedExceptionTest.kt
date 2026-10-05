package com.resilientpos.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnauthenticatedExceptionTest {

    @Test
    fun `스택 트레이스를 만들지 않고 원인도 싣지 않는다`() {
        val e = UnauthenticatedException()

        assertTrue(e.stackTrace.isEmpty(), "스택 트레이스가 채워졌다: ${e.stackTrace.size}")
        assertEquals("unauthenticated", e.message)
        assertEquals(null, e.cause)
    }
}
