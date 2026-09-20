package com.resilientpos.platform

import com.resilientpos.foundation.DomainException
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 공개 endpoint가 아직 없어서 테스트 전용 Controller로 응답 envelope와 오류 변환 계약을 고정한다.
 */
@WebMvcTest(ApiAdviceTestController::class)
class ApiAdviceTest(@Autowired private val mockMvc: MockMvc) {

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

    companion object {
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

    @GetMapping("/domain")
    fun domain(): Sample = throw DomainException("EMPTY_ORDER", "주문에는 line이 1개 이상 필요합니다")

    @GetMapping("/number")
    fun number(@RequestParam n: Int): Sample = Sample(n.toString())
}
