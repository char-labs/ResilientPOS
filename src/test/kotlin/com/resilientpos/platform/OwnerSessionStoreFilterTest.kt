package com.resilientpos.platform

import jakarta.servlet.FilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.serializer.SerializationException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.web.servlet.HandlerExceptionResolver

class OwnerSessionStoreFilterTest {

    private val resolved = mutableListOf<Exception>()
    private val resolver = HandlerExceptionResolver { _, _, _, ex -> resolved += ex; null }
    private val filter = OwnerSessionStoreFilter { resolver }
    private val request = MockHttpServletRequest("GET", "/owner/v1/stores")
    private val response = MockHttpServletResponse()

    @Test
    fun `체인이 DataAccessException을 던지면 응답을 비우고 SessionStoreUnavailableException으로 resolver에 넘긴다`() {
        val cause = RedisConnectionFailureException("redis down")
        response.setHeader("X-Partial", "1")

        filter.doFilter(request, response, FilterChain { _, _ -> throw cause })

        val thrown = resolved.single()
        assertTrue(thrown is SessionStoreUnavailableException, thrown.toString())
        assertEquals(cause, thrown.cause)
        assertNull(response.getHeader("X-Partial"))
    }

    @Test
    fun `응답이 이미 커밋됐으면 바꿀 수 없으므로 다시 던진다`() {
        response.flushBuffer()

        assertFailsWith<RedisConnectionFailureException> {
            filter.doFilter(request, response, FilterChain { _, _ -> throw RedisConnectionFailureException("redis down") })
        }
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `세션 값을 읽지 못한 SerializationException은 DataAccessException 계열이 아니라서 503으로 바꾸지 않는다`() {
        assertFailsWith<SerializationException> {
            filter.doFilter(request, response, FilterChain { _, _ -> throw SerializationException("bad") })
        }
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `등록은 owner 아래 경로만 걸고 Spring Session 필터보다 앞선다`() {
        val registration = PlatformConfiguration().ownerSessionStoreFilter(
            StaticListableBeanFactory(mapOf("handlerExceptionResolver" to resolver)).getBeanProvider(HandlerExceptionResolver::class.java),
        )

        assertEquals(listOf("/owner/*"), registration.urlPatterns.toList())
        assertTrue(registration.order < SessionRepositoryFilter.DEFAULT_ORDER, "order=${registration.order}")
    }
}
