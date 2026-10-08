package com.resilientpos.platform

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.terminal.TerminalManagement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.security.web.csrf.InvalidCsrfTokenException
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 공개 endpoint가 아직 없어서 테스트 전용 Controller로 응답 envelope와 오류 변환 계약을 고정한다.
 */
@ExtendWith(OutputCaptureExtension::class)
@WebMvcTest(ApiAdviceTestController::class)
class ApiAdviceTest(@Autowired private val mockMvc: MockMvc) {

    // 슬라이스에 단말 인증 인터셉터 설정이 실린다. 이 테스트의 경로는 v1이 아니라 인증을 타지 않지만 의존성은 채워야 한다.
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @Test
    fun `객체 응답은 SUCCESS envelope의 data로 나간다`() {
        mockMvc.get("/test-advice/object").andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SUCCESS") }
            jsonPath("$.data.value") { value("ok") }
            jsonPath("$.error") { value(null) }
        }
    }

    @Test
    fun `본문이 없는 응답은 data가 없는 SUCCESS envelope로 나간다`() {
        mockMvc.get("/test-advice/empty").andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SUCCESS") }
            jsonPath("$.data") { value(null) }
        }
    }

    @Test
    fun `처리하지 못한 예외는 메시지를 노출하지 않는 500 INTERNAL_ERROR다`() {
        mockMvc.get("/test-advice/boom").andExpect {
            status { isInternalServerError() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("INTERNAL_ERROR") }
            jsonPath("$.error.message") { value(not(containsString(SECRET))) }
        }
    }

    @Test
    fun `잠금 대기 초과와 버전 충돌은 메시지를 노출하지 않는 409 CONCURRENT_REQUEST다`() {
        listOf("pessimistic", "optimistic").forEach { kind ->
            mockMvc.get("/test-advice/concurrency/$kind").andExpect {
                status { isEqualTo(409) }
                jsonPath("$.result") { value("FAIL") }
                jsonPath("$.data") { value(null) }
                jsonPath("$.error.errorCode") { value("CONCURRENT_REQUEST") }
                jsonPath("$.error.message") { value(not(containsString(SECRET))) }
            }
        }
    }

    @Test
    fun `세션 저장소 장애는 원인 메시지를 노출하지 않는 503 SERVICE_UNAVAILABLE이고 ERROR로 남는다`(output: CapturedOutput) {
        mockMvc.get("/test-advice/session-store").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("SERVICE_UNAVAILABLE") }
            jsonPath("$.error.message") { value(not(containsString(SECRET))) }
        }

        assertTrue(output.all.contains("ERROR"), output.all)
        assertTrue(output.all.contains("errorCode=SERVICE_UNAVAILABLE"), output.all)
    }

    @Test
    fun `일반 DataAccessException은 그대로 500 INTERNAL_ERROR다`() {
        mockMvc.get("/test-advice/data-access").andExpect {
            status { isInternalServerError() }
            jsonPath("$.error.errorCode") { value("INTERNAL_ERROR") }
        }
    }

    @Test
    fun `허용하지 않는 method는 405 METHOD_NOT_ALLOWED다`() {
        mockMvc.post("/test-advice/object").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("METHOD_NOT_ALLOWED") }
        }
    }

    @Test
    fun `없는 경로는 404 NOT_FOUND다`() {
        mockMvc.get("/test-advice/missing").andExpect {
            status { isNotFound() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `타입이 맞지 않는 파라미터는 400 BAD_REQUEST다`() {
        mockMvc.get("/test-advice/number?n=abc").andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("BAD_REQUEST") }
        }
    }

    @Test
    fun `도메인 규칙 위반은 예외의 code와 message를 담은 400이다`() {
        mockMvc.get("/test-advice/domain").andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("EMPTY_ORDER") }
            jsonPath("$.error.message") { value("주문에는 line이 1개 이상 필요합니다") }
        }
    }

    @Test
    fun `도메인 규칙 위반의 kind가 HTTP 상태를 정한다`() {
        val expected = mapOf(
            DomainErrorKind.INVALID to 400,
            DomainErrorKind.NOT_FOUND to 404,
            DomainErrorKind.CONFLICT to 409,
            DomainErrorKind.UNPROCESSABLE to 422,
            DomainErrorKind.FORBIDDEN to 403,
        )
        assertEquals(DomainErrorKind.entries.toSet(), expected.keys)

        expected.forEach { (kind, httpStatus) ->
            mockMvc.get("/test-advice/domain/$kind").andExpect {
                status { isEqualTo(httpStatus) }
                jsonPath("$.result") { value("FAIL") }
                jsonPath("$.error.errorCode") { value("SAMPLE_$kind") }
                jsonPath("$.error.message") { value("$kind 사례") }
            }
        }
    }

    @Test
    fun `CSRF 거절은 403 ACCESS_DENIED이고 로그에 제출된 토큰도 스택도 남기지 않는다`(output: CapturedOutput) {
        mockMvc.get("/test-advice/csrf").andExpect {
            status { isForbidden() }
            jsonPath("$.error.errorCode") { value("ACCESS_DENIED") }
        }

        assertTrue(output.all.contains("errorCode=ACCESS_DENIED"), output.all)
        assertFalse(output.all.contains(SUBMITTED_TOKEN), "제출 토큰이 로그에 남았다")
        assertFalse(output.all.contains("InvalidCsrfTokenException"), "예외 스택이 로그에 남았다")
    }

    companion object {
        const val SUBMITTED_TOKEN = "submitted-csrf-token-value"
        const val SECRET = "secret-internal-detail"
    }
}

@RestController
@RequestMapping("/test-advice")
class ApiAdviceTestController {

    data class Sample(val value: String)

    @GetMapping("/object")
    fun sample(): Sample = Sample("ok")

    @GetMapping("/empty")
    fun empty() {
    }

    @GetMapping("/boom")
    fun boom(): Sample = throw IllegalStateException(ApiAdviceTest.SECRET)

    @GetMapping("/concurrency/{kind}")
    fun concurrency(@PathVariable kind: String): Sample = throw when (kind) {
        "pessimistic" -> CannotAcquireLockException(ApiAdviceTest.SECRET)
        else -> OptimisticLockingFailureException(ApiAdviceTest.SECRET)
    }

    @GetMapping("/session-store")
    fun sessionStore(): Sample = throw SessionStoreUnavailableException(RedisConnectionFailureException(ApiAdviceTest.SECRET))

    @GetMapping("/data-access")
    fun dataAccess(): Sample = throw RedisConnectionFailureException(ApiAdviceTest.SECRET)

    @GetMapping("/domain")
    fun domain(): Sample = throw DomainException("EMPTY_ORDER", "주문에는 line이 1개 이상 필요합니다")

    @GetMapping("/domain/{kind}")
    fun domainKind(@PathVariable kind: DomainErrorKind): Sample =
        throw DomainException("SAMPLE_$kind", "$kind 사례", kind)

    @GetMapping("/csrf")
    fun csrf(): Sample = throw InvalidCsrfTokenException(DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), ApiAdviceTest.SUBMITTED_TOKEN)

    @GetMapping("/number")
    fun number(@RequestParam n: Int): Sample = Sample(n.toString())
}
