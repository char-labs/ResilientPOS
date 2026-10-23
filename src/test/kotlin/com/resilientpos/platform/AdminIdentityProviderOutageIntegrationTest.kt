package com.resilientpos.platform

import com.jayway.jsonpath.JsonPath
import com.resilientpos.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 운영자 IdP에 닿지 못하는 상태를 검사한다. [OperatorTokens.IdpStub]을 import하지 않아 테스트 issuer(`.test`)가 해석되지 않으므로
 * 첫 토큰 검증의 issuer discovery가 실패한다. 다른 MockMvc 통합 테스트와 같은 설정이라 공유 컨텍스트를 그대로 쓴다.
 */
@ExtendWith(OutputCaptureExtension::class)
class AdminIdentityProviderOutageIntegrationTest(
    private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {

    @Test
    fun `issuer discovery에 실패하면 admin 요청은 HTML이 아니라 503 SERVICE_UNAVAILABLE envelope이고 ERROR 로그에 토큰이 없다`(output: CapturedOutput) {
        val bearer = OperatorTokens.bearer()
        val mark = output.all.length

        val response = mockMvc.get("/admin/v1/dead-letters") { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response

        assertEquals(503, response.status, response.contentAsString)
        assertTrue(response.contentType.orEmpty().startsWith("application/json"), response.contentType)
        assertEquals("SERVICE_UNAVAILABLE", JsonPath.read(response.contentAsString, "$.error.errorCode"))
        assertEquals(ErrorType.SERVICE_UNAVAILABLE.message, JsonPath.read(response.contentAsString, "$.error.message"))
        val out = output.all.substring(mark)
        val errorLine = out.lines().single { it.contains("errorCode=SERVICE_UNAVAILABLE") }
        assertTrue(errorLine.contains(" ERROR "), errorLine)
        // 헤더 조각은 alg·kid뿐이라 흔하다. 토큰마다 다른 payload·서명 조각이 출력 어디에도 없어야 한다.
        bearer.removePrefix("Bearer ").split(".").drop(1).forEach { assertFalse(out.contains(it), "token segment in output") }
    }
}
