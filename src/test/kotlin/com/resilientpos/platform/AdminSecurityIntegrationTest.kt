package com.resilientpos.platform

import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.util.Base64URL
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import jakarta.servlet.http.Cookie
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Controller가 없는 경로라 체인을 통과하면 404 envelope다. DLT endpoint(Kafka 읽기)와 무관하게 체인만 본다. */
private const val ADMIN_PATH = "/admin/v1/chain-probe"

/**
 * `/admin` 아래 운영자 체인을 실제 Boot decoder 구성(issuer·audience·서명·만료 검사)으로 검사한다. Docker가 필요하다.
 * IdP의 discovery·JWK Set 응답만 [OperatorTokens.IdpStub]이 대신 준다. 그 설정 때문에 컨텍스트가 하나 더 뜬다(받아들임).
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(OperatorTokens.IdpStub::class)
class AdminSecurityIntegrationTest(
    private val mockMvc: MockMvc,
    private val terminals: TerminalManagement,
) : PostgresIntegrationTest() {

    private fun callAdmin(authorization: String? = null, cookie: Cookie? = null): MockHttpServletResponse =
        mockMvc.get(ADMIN_PATH) {
            authorization?.let { header(HttpHeaders.AUTHORIZATION, it) }
            cookie?.let { cookie(it) }
        }.andReturn().response

    private fun assertUnauthenticated(response: MockHttpServletResponse, challenge: String?, case: String) {
        assertEquals(401, response.status, case)
        assertEquals(challenge, response.getHeader(HttpHeaders.WWW_AUTHENTICATE), case)
        assertEquals("UNAUTHENTICATED", JsonPath.read(response.contentAsString, "$.error.errorCode"), case)
        assertEquals(ErrorType.UNAUTHENTICATED.message, JsonPath.read(response.contentAsString, "$.error.message"), case)
    }

    @Test
    fun `issuer audience 서명 만료가 맞고 운영자 권한이 있는 토큰은 체인을 통과해 없는 경로의 404 envelope가 되고 Set-Cookie가 없다`() {
        val response = callAdmin(OperatorTokens.bearer())

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", JsonPath.read(response.contentAsString, "$.error.errorCode"))
        assertNull(response.getHeader(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `토큰이 없거나 서명 만료 issuer audience 중 하나가 틀리면 401 UNAUTHENTICATED와 WWW-Authenticate Bearer이고 Set-Cookie가 없다`() {
        val cases = mapOf(
            "토큰 없음" to null,
            "서명 틀림" to OperatorTokens.bearer(key = OperatorTokens.impostorKey),
            "만료" to OperatorTokens.bearer(expiresAt = Instant.now().minus(Duration.ofMinutes(5))),
            "다른 issuer" to OperatorTokens.bearer(issuer = "https://other-idp.test"),
            "다른 audience" to OperatorTokens.bearer(audience = "other-app"),
            "aud 없음" to OperatorTokens.bearer(audience = null),
            "서명 없음(alg none)" to unsigned(OperatorTokens.bearer()),
        )

        cases.forEach { (case, authorization) ->
            val response = callAdmin(authorization)
            assertUnauthenticated(response, UnauthenticatedException.BEARER, case)
            assertNull(response.getHeader(HttpHeaders.SET_COOKIE), case)
        }
    }

    @Test
    fun `만료 토큰과 서명 틀린 토큰의 401은 원인 예외와 메시지를 INFO로 남기고 토큰 조각은 출력에 없다`(output: CapturedOutput) {
        val cases = mapOf(
            "expired" to OperatorTokens.bearer(expiresAt = Instant.now().minus(Duration.ofMinutes(5))),
            "Invalid signature" to OperatorTokens.bearer(key = OperatorTokens.impostorKey),
        )

        cases.forEach { (reason, bearer) ->
            val mark = output.all.length
            assertUnauthenticated(callAdmin(bearer), UnauthenticatedException.BEARER, reason)

            val out = output.all.substring(mark)
            val line = out.lines().single { it.contains("operator unauthenticated") }
            assertTrue(line.contains(" INFO "), line)
            assertTrue(line.contains("uri=$ADMIN_PATH cause=InvalidBearerTokenException: "), line)
            assertTrue(line.contains(reason), line)
            bearer.removePrefix("Bearer ").split(".").drop(1).forEach { assertFalse(out.contains(it), "$reason token segment in output") }
        }
    }

    /** 같은 클레임에서 서명만 뺀(`alg: none`) 토큰. */
    private fun unsigned(bearer: String): String {
        val payload = bearer.removePrefix("Bearer ").split(".")[1]
        return "Bearer ${Base64URL.encode("""{"alg":"none"}""")}.$payload."
    }

    @Test
    fun `운영자 권한이 없는 토큰은 403 ACCESS_DENIED이고 Set-Cookie가 없다`() {
        val response = callAdmin(OperatorTokens.bearer(roles = listOf("resilientpos-viewer")))

        assertEquals(403, response.status)
        assertEquals("ACCESS_DENIED", JsonPath.read(response.contentAsString, "$.error.errorCode"))
        assertEquals(ErrorType.ACCESS_DENIED.message, JsonPath.read(response.contentAsString, "$.error.message"))
        assertNull(response.getHeader(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `점주 세션 쿠키나 단말 토큰으로 admin을 부르면 401이다`() {
        val owner = mockMvc.logInNewOwner()

        assertUnauthenticated(callAdmin(cookie = owner.session), UnauthenticatedException.BEARER, "점주 세션 쿠키")
        assertUnauthenticated(callAdmin(terminals.registerTerminal().bearer), UnauthenticatedException.BEARER, "단말 토큰")
    }

    @Test
    fun `운영자 토큰으로 owner와 v1을 부르면 401이다`() {
        val operator = OperatorTokens.bearer()

        val owner = mockMvc.get("/owner/v1/stores") { header(HttpHeaders.AUTHORIZATION, operator) }.andReturn().response
        val terminal = mockMvc.post("/v1/orders") {
            header(HttpHeaders.AUTHORIZATION, operator)
            contentType = MediaType.APPLICATION_JSON
            content = """{ "storeId": "${UUID.randomUUID()}" }"""
        }.andReturn().response

        assertUnauthenticated(owner, null, "owner")
        assertUnauthenticated(terminal, UnauthenticatedException.BEARER, "v1")
    }
}
