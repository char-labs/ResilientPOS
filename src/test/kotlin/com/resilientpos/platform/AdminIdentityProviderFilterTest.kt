package com.resilientpos.platform

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException
import org.springframework.web.servlet.HandlerExceptionResolver

class AdminIdentityProviderFilterTest {

    private val resolved = mutableListOf<Exception>()
    private val resolver = HandlerExceptionResolver { _, _, _, ex -> resolved += ex; null }
    private val filter = AdminIdentityProviderFilter { resolver }
    private val request = MockHttpServletRequest("GET", "/admin/v1/dead-letters")
    private val response = MockHttpServletResponse()

    private fun assertResolvedAsUnavailable(cause: RuntimeException) {
        filter.doFilter(request, response, FilterChain { _, chainResponse ->
            // Security 필터가 쓴 헤더와 아직 커밋되지 않은 본문
            (chainResponse as HttpServletResponse).setHeader("X-Content-Type-Options", "nosniff")
            chainResponse.writer.write("partial")
            throw cause
        })

        val thrown = resolved.single()
        assertTrue(thrown is DependencyUnavailableException, thrown.toString())
        assertEquals(cause, thrown.cause)
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `issuer discovery 실패인 JwtDecoderInitializationException은 헤더는 두고 본문만 비워 DependencyUnavailableException으로 resolver에 넘긴다`() {
        assertResolvedAsUnavailable(JwtDecoderInitializationException("Failed to lazily resolve the supplied JwtDecoder instance", IllegalArgumentException("issuer")))
    }

    @Test
    fun `JWK Set 조회 실패인 AuthenticationServiceException은 헤더는 두고 본문만 비워 DependencyUnavailableException으로 resolver에 넘긴다`() {
        assertResolvedAsUnavailable(AuthenticationServiceException("Couldn't retrieve remote JWK set"))
    }

    @Test
    fun `응답이 이미 커밋됐으면 바꿀 수 없으므로 다시 던진다`() {
        response.flushBuffer()

        assertFailsWith<AuthenticationServiceException> {
            filter.doFilter(request, response, FilterChain { _, _ -> throw AuthenticationServiceException("jwks down") })
        }
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `IdP와 무관한 예외는 503으로 바꾸지 않고 그대로 던진다`() {
        assertFailsWith<IllegalStateException> {
            filter.doFilter(request, response, FilterChain { _, _ -> throw IllegalStateException("bug") })
        }
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `등록은 admin 아래 경로만 걸고 설정된 Spring Security 필터 순서 바로 앞이다`() {
        fun register(security: SecurityFilterProperties) = PlatformConfiguration().adminIdentityProviderFilter(
            StaticListableBeanFactory(mapOf("handlerExceptionResolver" to resolver)).getBeanProvider(HandlerExceptionResolver::class.java),
            security,
        )

        val byDefault = register(SecurityFilterProperties())
        val configured = register(SecurityFilterProperties().apply { order = 10 })

        assertEquals(listOf("/admin/*"), byDefault.urlPatterns.toList())
        assertEquals(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1, byDefault.order)
        assertEquals(9, configured.order)
    }
}
