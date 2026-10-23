package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import jakarta.servlet.http.Cookie
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.context.ApplicationContext
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * `/owner` 아래 점주 세션 체인을 실제 Postgres·Redis·Spring Session으로 검사한다. Docker가 필요하다.
 * MockMvc는 쿠키를 보관하지 않으므로 응답의 `Set-Cookie`를 다음 요청에 직접 싣는다.
 */
class OwnerAuthenticationIntegrationTest(
    private val mockMvc: MockMvc,
    private val terminals: TerminalManagement,
    private val sessionRepository: SessionRepository<*>,
    private val jdbcTemplate: JdbcTemplate,
    private val context: ApplicationContext,
) : PostgresIntegrationTest() {

    // Redis 저장소는 SessionRepository<RedisSession>이라 제네릭 주입이 맞지 않는다. 이 테스트는 Session 계약만 쓴다.
    @Suppress("UNCHECKED_CAST")
    private val sessions get() = sessionRepository as SessionRepository<Session>

    private fun listStores(cookie: Cookie? = null): ResultActionsDsl =
        mockMvc.get("/owner/v1/stores") { cookie?.let { cookie(it) } }

    private fun MockMvcResultMatchersDsl.unauthenticatedEnvelope(challenge: String?) {
        status { isUnauthorized() }
        if (challenge == null) header { doesNotExist(HttpHeaders.WWW_AUTHENTICATE) } else header { string(HttpHeaders.WWW_AUTHENTICATE, challenge) }
        jsonPath("$.result") { value("FAIL") }
        jsonPath("$.data") { value(null) }
        jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        jsonPath("$.error.message") { value("인증이 필요합니다.") }
    }

    private fun sessionId(cookie: Cookie): String = String(Base64.getDecoder().decode(cookie.value))

    private fun logIn(email: String, password: String, csrf: String, session: Cookie? = null): ResultActionsDsl = mockMvc.post("/owner/v1/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{ "email": "$email", "password": "$password" }"""
        withCsrf(csrf)
        session?.let { cookie(it) }
    }

    @Test
    fun `가입 CSRF 로그인을 거친 세션 쿠키로 인증 경로가 200이고 이메일은 정규화되며 로그인 뒤 CSRF 토큰이 새로 만들어진다`() {
        val email = uniqueOwnerEmail()
        val csrf = mockMvc.issueCsrfToken()

        val signUp = mockMvc.post("/owner/v1/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "email": "  ${email.uppercase()} ", "password": "$OWNER_PASSWORD" }"""
            withCsrf(csrf)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.email") { value(email) }
        }.andReturn().response
        assertNull(signUp.getCookie(SESSION_COOKIE), "가입은 로그인시키지 않는다")

        val login = logIn("  ${email.uppercase()} ", OWNER_PASSWORD, csrf).andExpect {
            status { isOk() }
            jsonPath("$.data.email") { value(email) }
        }.andReturn().response
        val sessionCookie = assertNotNull(login.getCookie(SESSION_COOKIE))
        assertNotEquals(csrf, assertNotNull(login.getCookie("__Host-XSRF-TOKEN")).value, "로그인 뒤 CSRF 토큰이 새로 만들어진다")

        listStores(sessionCookie).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SUCCESS") }
        }
    }

    @Test
    fun `로그인하면 로그인 전 세션 ID가 폐기되고 다른 ID의 세션이 발급된다`() {
        val email = uniqueOwnerEmail()
        val csrf = mockMvc.issueCsrfToken()
        mockMvc.post("/owner/v1/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "email": "$email", "password": "$OWNER_PASSWORD" }"""
            withCsrf(csrf)
        }.andExpect { status { isCreated() } }
        val before = sessions.createSession().also { sessions.save(it) }
        val beforeCookie = Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(before.id.toByteArray()))

        val login = logIn(email, OWNER_PASSWORD, csrf, beforeCookie).andExpect { status { isOk() } }.andReturn().response

        val afterId = sessionId(assertNotNull(login.getCookie(SESSION_COOKIE)))
        assertNotEquals(before.id, afterId)
        assertNull(sessions.findById(before.id), "세션 고정을 막으려면 로그인 전 ID는 폐기돼야 한다")
        assertNotNull(sessions.findById(afterId))
    }

    @Test
    fun `로그아웃은 204이고 같은 쿠키는 그 뒤 401이며 세션이 삭제된다`() {
        val owner = mockMvc.logInNewOwner()
        listStores(owner.session).andExpect { status { isOk() } }

        val logout = mockMvc.post("/owner/v1/logout") { asOwner(owner) }.andExpect {
            status { isNoContent() }
            content { string("") }
        }.andReturn().response

        assertEquals(0, logout.getCookie("__Host-XSRF-TOKEN")?.maxAge, "로그아웃은 CSRF 쿠키를 만료시킨다: ${logout.getHeaders("Set-Cookie")}")
        listStores(owner.session).andExpect { unauthenticatedEnvelope(null) }
        assertNull(sessions.findById(sessionId(owner.session)))
    }

    @Test
    fun `세션이 없으면 401 UNAUTHENTICATED이고 WWW-Authenticate가 없으며 세션을 만들지 않는다`() {
        val response = listStores().andExpect { unauthenticatedEnvelope(null) }.andReturn().response

        assertNull(response.getCookie(SESSION_COOKIE))
    }

    @Test
    fun `모르는 세션 쿠키도 401이다`() {
        listStores(Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString("no-such-session".toByteArray())))
            .andExpect { unauthenticatedEnvelope(null) }
    }

    @Test
    fun `CSRF 토큰이 없거나 틀리면 403 ACCESS_DENIED이고 계정을 만들지 않는다`() {
        val email = uniqueOwnerEmail()
        val body = """{ "email": "$email", "password": "$OWNER_PASSWORD" }"""

        mockMvc.post("/owner/v1/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("ACCESS_DENIED") }
            jsonPath("$.error.message") { value(ErrorType.ACCESS_DENIED.message) }
        }
        mockMvc.post("/owner/v1/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            cookie(Cookie("__Host-XSRF-TOKEN", mockMvc.issueCsrfToken()))
            header("X-XSRF-TOKEN", mockMvc.issueCsrfToken())
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.errorCode") { value("ACCESS_DENIED") }
        }

        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_owner where email = ?", email))
    }

    @Test
    fun `로그인한 점주도 CSRF 토큰 없이 로그아웃하면 403이고 세션이 남는다`() {
        val owner = mockMvc.logInNewOwner()

        mockMvc.post("/owner/v1/logout") { cookie(owner.session) }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.errorCode") { value("ACCESS_DENIED") }
        }

        listStores(owner.session).andExpect { status { isOk() } }
    }

    @Test
    fun `틀린 비밀번호와 없는 계정은 같은 401이고 WWW-Authenticate가 없으며 세션을 만들지 않는다`() {
        val owner = mockMvc.logInNewOwner()
        val csrf = mockMvc.issueCsrfToken()

        val wrong = logIn(owner.email, "wrong-password-value", csrf).andExpect { unauthenticatedEnvelope(null) }.andReturn().response
        val unknown = logIn(uniqueOwnerEmail(), OWNER_PASSWORD, csrf).andExpect { unauthenticatedEnvelope(null) }.andReturn().response

        assertNull(wrong.getCookie(SESSION_COOKIE))
        assertEquals(wrong.contentAsString, unknown.contentAsString)
    }

    @Test
    fun `단말 토큰으로 owner를 부르면 401이다`() {
        mockMvc.get("/owner/v1/stores") { header("Authorization", terminals.registerTerminal().bearer) }
            .andExpect { unauthenticatedEnvelope(null) }
    }

    @Test
    fun `점주 세션으로 v1을 부르면 단말 인터셉터가 Bearer 401로 거절한다`() {
        val owner = mockMvc.logInNewOwner()

        mockMvc.post("/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            asOwner(owner)
            content = """{ "storeId": "${UUID.randomUUID()}" }"""
        }.andExpect { unauthenticatedEnvelope("Bearer") }
    }

    @Test
    fun `actuator health는 세션 없이 200이다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun `Boot 기본 사용자를 만들지 않는다`() {
        assertEquals(emptyList(), context.getBeanNamesForType(UserDetailsService::class.java).toList())
    }
}
