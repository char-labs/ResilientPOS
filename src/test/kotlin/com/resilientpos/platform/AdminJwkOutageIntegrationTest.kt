package com.resilientpos.platform

import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.resilientpos.PostgresIntegrationTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.client.RestTemplate

private val jwksAvailable = AtomicBoolean(true)

/** Controller가 없는 경로라 체인을 통과하면 404 envelope다. 뒤에 생길 `/admin` endpoint와 무관하게 decoder만 초기화한다. */
private const val PROBE_PATH = "/admin/v1/chain-probe"

/** Spring Security 기본 헤더 중 모든 응답에 붙는 것(HSTS는 https 요청에만 붙는다). */
private val SECURITY_HEADERS = listOf("X-Content-Type-Options", "X-Frame-Options", "Cache-Control", "Pragma", "Expires")

/**
 * decoder 초기화(discovery와 JWK Set 첫 조회)가 끝난 뒤 JWK Set 재조회가 실패하는 IdP 장애를 검사한다. 이 경로의 예외는 초기화 실패와 달리
 * `AuthenticationServiceException`이라, resource server 실패 처리기가 다시 던지는 동작에 기대는지 실제 체인으로 고정한다.
 * JWK 응답을 바꾸는 설정 때문에 컨텍스트가 하나 더 뜬다(받아들임).
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Import(AdminJwkOutageIntegrationTest.JwkOutageStub::class)
class AdminJwkOutageIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {

    @AfterEach
    fun restoreJwks() = jwksAvailable.set(true)

    @Test
    fun `IdP 장애의 503 응답에도 정상 admin 응답과 같은 Security 헤더가 남는다`() {
        val normal = mockMvc.get(PROBE_PATH) { header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer()) }.andReturn().response
        assertEquals(404, normal.status, normal.contentAsString)
        jwksAvailable.set(false)
        val rotated = OperatorTokens.bearer(key = RSAKeyGenerator(2048).keyID("rotated-key").generate())

        val unavailable = mockMvc.get(PROBE_PATH) { header(HttpHeaders.AUTHORIZATION, rotated) }.andReturn().response

        assertEquals(503, unavailable.status, unavailable.contentAsString)
        SECURITY_HEADERS.forEach { name ->
            assertNotNull(normal.getHeader(name), name)
            assertEquals(normal.getHeaderValues(name), unavailable.getHeaderValues(name), name)
        }
    }

    @Test
    fun `decoder 초기화 뒤 JWK Set 재조회가 실패하면 admin 요청은 503 SERVICE_UNAVAILABLE envelope이고 토큰은 출력에 없다`(output: CapturedOutput) {
        val first = mockMvc.get(PROBE_PATH) { header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer()) }.andReturn().response
        assertEquals(404, first.status, first.contentAsString)
        jwksAvailable.set(false)
        // 캐시에 없는 kid라 decoder가 JWK Set을 다시 조회한다.
        val bearer = OperatorTokens.bearer(key = RSAKeyGenerator(2048).keyID("rotated-key").generate())
        val mark = output.all.length

        val response = mockMvc.get(PROBE_PATH) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response

        assertEquals(503, response.status, response.contentAsString)
        assertEquals("SERVICE_UNAVAILABLE", JsonPath.read(response.contentAsString, "$.error.errorCode"))
        val out = output.all.substring(mark)
        bearer.removePrefix("Bearer ").split(".").drop(1).forEach { assertFalse(out.contains(it), "token segment in output") }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class JwkOutageStub {
        @Bean
        fun operatorIdpJwkOutage() = JwkSetUriJwtDecoderBuilderCustomizer { builder ->
            builder.restOperations(RestTemplate(OperatorTokens.idpResponses(jwksAvailable::get)))
        }
    }
}
