package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * `/admin` 아래 운영자 JWT 검증 체인을 실제 Boot 설정(issuer-uri, audiences, authorities-claim-name)으로 검사한다.
 * 아직 `/admin` 핸들러가 없어서 인증을 통과한 요청은 404 envelope로 끝난다. 인증 결과만 본다.
 * Docker가 필요하다.
 */
@AutoConfigureMockMvc
class OperatorAuthenticationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val terminals: TerminalManagement,
) : PostgresIntegrationTest() {

    private fun admin(authorization: String?): ResultActionsDsl = mockMvc.get("/admin/v1/terminals") {
        authorization?.let { header("Authorization", it) }
    }

    private fun MockMvcResultMatchersDsl.unauthenticatedEnvelope() {
        status { isUnauthorized() }
        header { string("WWW-Authenticate", "Bearer") }
        jsonPath("$.result") { value("FAIL") }
        jsonPath("$.data") { value(null) }
        jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        jsonPath("$.error.message") { value("인증이 필요합니다.") }
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED와 WWW-Authenticate Bearer다`() {
        admin(null).andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `다른 키로 서명한 토큰은 401이다`() {
        admin("Bearer ${OperatorJwt.token(key = OperatorJwt.otherKey())}").andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `만료된 토큰은 401이다`() {
        admin("Bearer ${OperatorJwt.token(expiresAt = Instant.now().minusSeconds(600))}").andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `iss가 다른 토큰은 401이다`() {
        admin("Bearer ${OperatorJwt.token(issuer = "http://evil.example/realms/resilientpos")}").andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `aud가 다른 토큰은 401이다`() {
        admin("Bearer ${OperatorJwt.token(audience = "other-client")}").andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `OPERATOR 역할이 없는 유효한 토큰은 403 ACCESS_DENIED다`() {
        admin("Bearer ${OperatorJwt.token(roles = listOf("VIEWER"))}").andExpect {
            status { isForbidden() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("ACCESS_DENIED") }
            jsonPath("$.error.message") { value(ErrorType.ACCESS_DENIED.message) }
        }
    }

    @Test
    fun `OPERATOR 토큰은 인증을 통과해 핸들러가 없으면 404 envelope다`() {
        admin("Bearer ${OperatorJwt.token()}").andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `단말 토큰으로 admin을 부르면 401이다`() {
        admin(terminals.registerTerminal().bearer).andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `운영자 JWT로 v1을 부르면 단말 인터셉터가 401로 거절한다`() {
        mockMvc.post("/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer ${OperatorJwt.token()}")
            content = """{ "storeId": "${UUID.randomUUID()}" }"""
        }.andExpect { unauthenticatedEnvelope() }
    }

    @Test
    fun `actuator health는 토큰 없이 200이다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun jwks(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri") { OperatorJwt.jwksUri }
        }
    }
}
