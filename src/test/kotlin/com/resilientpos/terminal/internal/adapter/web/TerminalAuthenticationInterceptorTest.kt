package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalFixtures.TOKEN
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.internal.adapter.redis.RedisTerminalRateLimiter
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verify
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
@ExtendWith(OutputCaptureExtension::class)
class TerminalAuthenticationInterceptorTest(private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @MockitoBean
    private lateinit var rateLimiter: RedisTerminalRateLimiter

    private val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID()))

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(terminal)
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
        verifyNoInteractions(rateLimiter)
    }

    @Test
    fun `형식은 맞지만 모르는 토큰이면 limiter를 부르지 않고 401이다`() {
        get("Bearer ${"B".repeat(43)}").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        }

        verifyNoInteractions(rateLimiter)
    }

    @Test
    fun `limiter가 거절하면 429 RATE_LIMITED와 Retry-After이고 Controller에 닿지 않으며 advice는 로그를 남기지 않는다`(output: CapturedOutput) {
        val mark = output.all.length // 이 테스트가 낸 출력만 본다. 클래스 단위 캡처에는 앞 테스트의 출력도 남는다
        val reason = "scope=terminal terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value}"
        willThrow(RateLimitedException(2, reason)).given(rateLimiter).acquire(terminal)
        InterceptorTestController.calls = 0

        get("Bearer $TOKEN").andExpect {
            status { isTooManyRequests() }
            header { string("Retry-After", "2") }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("RATE_LIMITED") }
            jsonPath("$.error.message") { value("요청이 많아 잠시 후 다시 시도해 주세요.") }
        }

        assertEquals(0, InterceptorTestController.calls, "거절된 요청이 Controller에 닿았다")
        // 거절 로그는 limiter가 scope별 10초에 한 줄로 남긴다. advice가 요청마다 남기면 Retry-After를 무시하는 단말이 로그를 요청 수만큼 늘린다.
        val logged = output.all.substring(mark)
        assertFalse(logged.contains("errorCode=RATE_LIMITED"), "advice가 429를 로그로 남겼다")
        assertFalse(logged.contains(RateLimitedException::class.java.name), "예외 줄이 찍혔다")
        assertFalse(logged.contains(TOKEN), "토큰 값(Authorization 헤더 값)이 출력에 남았다")
    }
}

@RestController
@RequestMapping("/v1/test-interceptor")
class InterceptorTestController {
    @GetMapping
    fun ok(): Sample {
        calls++
        return Sample("ok")
    }

    data class Sample(val value: String)

    companion object {
        @Volatile
        var calls = 0
    }
}
