package com.resilientpos.platform

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitedExceptionTest {

    @Test
    fun `스택 트레이스를 만들지 않는다`() {
        assertTrue(RateLimitedException(1, "scope=terminal").stackTrace.isEmpty())
    }

    @Test
    fun `Retry-After가 1 미만이면 만들지 못한다`() {
        assertFailsWith<IllegalArgumentException> { RateLimitedException(0, "scope=terminal") }
    }
}
