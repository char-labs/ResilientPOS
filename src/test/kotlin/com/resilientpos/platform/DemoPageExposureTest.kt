package com.resilientpos.platform

import com.resilientpos.terminal.MockTerminalRateLimiter
import com.resilientpos.terminal.TerminalManagement
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

private const val DEMO_PAGE = "/terminal/index.html"

/**
 * 시연 화면은 인증 없이 열리고 sync endpoint를 부른다. 운영 jar에 실려도 기본 프로필에서는 서빙되지 않아야 한다.
 */
@MockTerminalRateLimiter
@WebMvcTest(ApiAdviceTestController::class)
class DemoPageHiddenByDefaultTest(@Autowired private val mockMvc: MockMvc) {

    // 웹 슬라이스가 인증 인터셉터를 함께 띄우므로 그 의존을 채운다. 이 경로는 인터셉터 범위(v1) 밖이다.
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @Test
    fun `기본 프로필에서는 시연 화면이 404다`() {
        mockMvc.get(DEMO_PAGE).andExpect {
            status { isNotFound() }
            jsonPath("$.result") { value("FAIL") }
        }
    }
}

@MockTerminalRateLimiter
@WebMvcTest(ApiAdviceTestController::class)
@ActiveProfiles("demo")
class DemoPageOpenInDemoProfileTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @Test
    fun `demo 프로필에서는 시연 화면을 서빙한다`() {
        mockMvc.get(DEMO_PAGE).andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("text/html") }
        }
    }
}
