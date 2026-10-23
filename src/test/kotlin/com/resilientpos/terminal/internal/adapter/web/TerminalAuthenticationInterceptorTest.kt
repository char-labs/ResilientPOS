package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalFixtures.TOKEN
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.internal.adapter.redis.RedisTerminalRateLimiter
import com.resilientpos.terminal.internal.adapter.web.TerminalInFlightLimiter.Kind
import java.util.concurrent.Callable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verify
import org.springframework.http.HttpMethod
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** 인터셉터의 헤더 해석을 테스트 전용 v1 Controller로 검사한다. 응답 변환은 실제 `ApiControllerAdvice`를 거친다. */
@WebMvcTest(InterceptorTestController::class, InterceptorSyncTestController::class)
@ExtendWith(OutputCaptureExtension::class)
@MockitoBean(types = [TerminalManagement::class, RedisTerminalRateLimiter::class, TerminalInFlightLimiter::class])
class TerminalAuthenticationInterceptorTest(
    private val mockMvc: MockMvc,
    private val terminals: TerminalManagement,
    private val rateLimiter: RedisTerminalRateLimiter,
    private val inFlight: TerminalInFlightLimiter,
) {

    private val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID()))

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(terminal)
    }

    private fun get(authorization: String, path: String = "/v1/test-interceptor"): ResultActionsDsl =
        mockMvc.get(path) { header("Authorization", authorization) }

    /** 동시 상한은 GET·HEAD가 아닌 요청만 센다. 자리를 잡는지 보는 테스트는 POST로 보낸다. */
    private fun post(authorization: String, path: String = "/v1/test-interceptor"): ResultActionsDsl =
        mockMvc.post(path) { header("Authorization", authorization) }

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
        verifyNoInteractions(inFlight) // characterization: 인증 실패는 처음부터 자리를 잡지 않는다
    }

    @Test
    fun `형식은 맞지만 모르는 토큰이면 limiter를 부르지 않고 401이다`() {
        get("Bearer ${"B".repeat(43)}").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        }

        verifyNoInteractions(rateLimiter)
        verifyNoInteractions(inFlight)
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

    @Test
    fun `성공 요청은 동시 상한 빈도 상한 Controller 순서로 지나고 끝나면 REQUEST 자리를 한 번 푼다`() {
        InterceptorTestController.calls = 0

        post("Bearer $TOKEN").andExpect { status { isOk() } }

        inOrder(inFlight, rateLimiter).apply {
            verify(inFlight).acquire(terminal, Kind.REQUEST)
            verify(rateLimiter).acquire(terminal)
        }
        assertEquals(1, InterceptorTestController.calls)
        verify(inFlight, times(1)).release(terminal, Kind.REQUEST)
    }

    @Test
    fun `단말 sync 경로 패턴은 SYNC 묶음으로 자리를 잡고 푼다`() {
        mockMvc.put("/v1/terminals/${terminal.terminalId.value}/sync") { header("Authorization", "Bearer $TOKEN") }
            .andExpect { status { isOk() } }

        verify(inFlight).acquire(terminal, Kind.SYNC)
        verify(inFlight).release(terminal, Kind.SYNC)
        verify(inFlight, never()).acquire(terminal, Kind.REQUEST)
    }

    @Test
    fun `동시 상한이 거절하면 429와 Retry-After 1이고 Controller와 빈도 limiter와 release를 부르지 않는다`() {
        willThrow(RateLimitedException(1, "kind=request")).given(inFlight).acquire(terminal, Kind.REQUEST)
        InterceptorTestController.calls = 0

        post("Bearer $TOKEN").andExpect {
            status { isTooManyRequests() }
            header { string("Retry-After", "1") }
            jsonPath("$.error.errorCode") { value("RATE_LIMITED") }
        }

        assertEquals(0, InterceptorTestController.calls, "거절된 요청이 Controller에 닿았다")
        verifyNoInteractions(rateLimiter)
        verify(inFlight, never()).release(terminal, Kind.REQUEST)
    }

    @Test
    fun `빈도 상한이 거절하면 잡은 자리를 푼다`() {
        willThrow(RateLimitedException(2, "scope=terminal")).given(rateLimiter).acquire(terminal)

        post("Bearer $TOKEN").andExpect { status { isTooManyRequests() } }

        verify(inFlight).acquire(terminal, Kind.REQUEST)
        verify(inFlight, times(1)).release(terminal, Kind.REQUEST)
    }

    @Test
    fun `handler가 예외를 던져도 자리를 한 번 푼다`() {
        runCatching { mockMvc.post("/v1/test-interceptor/fail") { header("Authorization", "Bearer $TOKEN") } }

        verify(inFlight).acquire(terminal, Kind.REQUEST)
        verify(inFlight, times(1)).release(terminal, Kind.REQUEST)
    }

    @Test
    fun `비동기 dispatch는 자리와 인증과 빈도 상한을 다시 하지 않고 마지막에 한 번 푼다`() {
        val started = post("Bearer $TOKEN", "/v1/test-interceptor/async").andExpect { request { asyncStarted() } }.andReturn()
        verify(inFlight, never()).release(terminal, Kind.REQUEST) // 비동기가 끝나기 전에는 자리를 쥐고 있다

        mockMvc.perform(asyncDispatch(started)).andExpect(MockMvcResultMatchers.status().isOk)

        verify(inFlight, times(1)).acquire(terminal, Kind.REQUEST)
        verify(inFlight, times(1)).release(terminal, Kind.REQUEST)
        verify(terminals, times(1)).authenticate(TOKEN)
        verify(rateLimiter, times(1)).acquire(terminal)
    }

    @Test
    fun `비동기 dispatch는 인증과 빈도 상한을 다시 하지 않는다`() {
        val started = post("Bearer $TOKEN", "/v1/test-interceptor/async").andExpect { request { asyncStarted() } }.andReturn()
        given(terminals.authenticate(TOKEN)).willReturn(null)
        willThrow(RateLimitedException(1, "scope=terminal")).given(rateLimiter).acquire(terminal)

        val result = mockMvc.perform(asyncDispatch(started)).andReturn()

        assertEquals(200, result.response.status, "handler가 일한 뒤 401·429를 내면 429면 처리되지 않았다는 계약이 깨진다")
        verify(inFlight, times(1)).release(terminal, Kind.REQUEST)
    }

    @Test
    fun `GET과 HEAD는 자리를 잡지 않고 인증과 빈도 상한만 한다`() {
        for (method in listOf(HttpMethod.GET, HttpMethod.HEAD)) {
            mockMvc.request(method, "/v1/test-interceptor") { header("Authorization", "Bearer $TOKEN") }
                .andExpect { status { isOk() } }
        }

        verifyNoInteractions(inFlight)
        verify(rateLimiter, times(2)).acquire(terminal)
    }

    @Test
    fun `첫 REQUEST dispatch는 속성이 미리 있어도 인증하고 실패하면 401이며 limiter를 부르지 않는다`() {
        InterceptorTestController.calls = 0

        mockMvc.post("/v1/test-interceptor") {
            requestAttr(TerminalAuthenticationInterceptor.ATTRIBUTE, terminal)
        }.andExpect { status { isUnauthorized() } }

        assertEquals(0, InterceptorTestController.calls, "속성이 있다는 이유로 인증 없이 handler에 닿았다")
        verifyNoInteractions(inFlight)
        verifyNoInteractions(rateLimiter)
    }

    @Test
    fun `첫 REQUEST dispatch는 속성이 미리 있어도 인증이 통과하면 자리를 잡는다`() {
        mockMvc.post("/v1/test-interceptor") {
            header("Authorization", "Bearer $TOKEN")
            requestAttr(TerminalAuthenticationInterceptor.ATTRIBUTE, terminal)
        }.andExpect { status { isOk() } }

        verify(inFlight).acquire(terminal, Kind.REQUEST)
        verify(rateLimiter).acquire(terminal)
    }
}

@RestController
@RequestMapping("/v1/test-interceptor")
class InterceptorTestController {
    @RequestMapping(method = [RequestMethod.GET, RequestMethod.POST])
    fun ok(): Sample {
        calls++
        return Sample("ok")
    }

    @RequestMapping("/fail", method = [RequestMethod.GET, RequestMethod.POST])
    fun fail(): Sample = throw IllegalStateException("handler 실패")

    @RequestMapping("/async", method = [RequestMethod.GET, RequestMethod.POST])
    fun async(): Callable<Sample> = Callable { Sample("async") }

    data class Sample(val value: String)

    companion object {
        @Volatile
        var calls = 0
    }
}

/** 진짜 `SyncController`는 이 슬라이스에 없다. 같은 매핑 패턴으로 SYNC 묶음 판별만 검사한다(실제 앱에서는 POST 매핑과 겹치지 않게 PUT). */
@RestController
class InterceptorSyncTestController {
    @PutMapping("/v1/terminals/{terminalId}/sync")
    fun sync(@PathVariable terminalId: String) = InterceptorTestController.Sample("sync")
}
