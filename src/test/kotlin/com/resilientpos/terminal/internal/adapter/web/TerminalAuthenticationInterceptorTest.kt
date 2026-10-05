package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalFixtures.TOKEN
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import java.util.UUID
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.BDDMockito.given
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인터셉터의 헤더 해석을 테스트 전용 v1 Controller로 검사한다. 응답 변환은 실제 `ApiControllerAdvice`를 거친다. */
@WebMvcTest(InterceptorTestController::class)
class TerminalAuthenticationInterceptorTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID())))
    }

    private fun get(authorization: String): ResultActionsDsl =
        mockMvc.get("/v1/test-interceptor") { header("Authorization", authorization) }

    @Test
    fun `Bearer 스킴은 대소문자를 구분하지 않는다`() {
        for (scheme in listOf("Bearer", "bearer", "BEARER")) {
            get("$scheme $TOKEN").andExpect { status { isOk() } }
        }
    }

    @Test
    fun `형식이 토큰과 다른 값은 authenticate를 부르지 않고 같은 401이다`() {
        val invalid = listOf(
            "Bearer",
            "Bearer ",
            "Bearer    ",
            "Bearer unknown-token",
            "Bearer ${TOKEN.dropLast(1)}",
            "Bearer ${TOKEN}A",
            "Bearer ${TOKEN.dropLast(1)}=",
            "Bearer ${TOKEN.dropLast(1)}+",
            "Bearer $TOKEN extra",
            "Basic $TOKEN",
        )
        for (authorization in invalid) {
            get(authorization).andExpect {
                status { isUnauthorized() }
                header { string("WWW-Authenticate", "Bearer") }
                jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
                jsonPath("$.error.message") { value("인증이 필요합니다.") }
            }
        }

        verify(terminals, never()).authenticate(anyString())
    }
}

@RestController
@RequestMapping("/v1/test-interceptor")
class InterceptorTestController {
    @GetMapping
    fun ok(): Sample = Sample("ok")

    data class Sample(val value: String)
}
