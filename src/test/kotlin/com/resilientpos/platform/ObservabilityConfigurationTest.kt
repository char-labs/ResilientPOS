package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertContains
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * LGTM이 가져갈 metric 엔드포인트가 열려 있고, 그 외 Actuator 엔드포인트는 닫혀 있는지 본다.
 * 테스트는 기본적으로 metric export를 끄므로 `@AutoConfigureMetrics`로 운영과 같게 켠다.
 */
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ObservabilityConfigurationTest(
    @Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {

    @Test
    fun `prometheus 엔드포인트가 application 태그가 붙은 요청 metric을 돌려준다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }

        val body = mockMvc.get("/actuator/prometheus")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val requestMetric = body.lines().first { it.startsWith("http_server_requests_seconds_count") }
        assertContains(requestMetric, """application="ResilientPOS"""")
    }

    @Test
    fun `health와 prometheus 외의 Actuator 엔드포인트는 열지 않는다`() {
        mockMvc.get("/actuator/env").andExpect { status { isNotFound() } }
    }
}
