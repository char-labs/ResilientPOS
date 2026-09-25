package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
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
    fun `prometheus 엔드포인트가 application 태그와 지연 histogram이 붙은 요청 metric을 돌려준다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }

        val body = mockMvc.get("/actuator/prometheus")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.TEXT_PLAIN) }
            }
            .andReturn().response.contentAsString

        val requestMetric = body.lines().firstOrNull { it.startsWith("http_server_requests_seconds_count") }
        assertNotNull(requestMetric, "http_server_requests_seconds_count가 없다. metric export 설정을 확인한다")
        assertContains(requestMetric, """application="ResilientPOS"""")
        assertContains(body, "# TYPE http_server_requests_seconds histogram")
        // p95 같은 분위수는 bucket이 있어야 Prometheus의 histogram_quantile로 계산할 수 있다.
        assertNotNull(
            body.lines().firstOrNull { it.startsWith("http_server_requests_seconds_bucket") },
            "http_server_requests_seconds_bucket이 없다. percentiles-histogram 설정을 확인한다",
        )
    }

    @Test
    fun `health와 prometheus 외의 Actuator 엔드포인트는 열지 않는다`() {
        mockMvc.get("/actuator/env").andExpect { status { isNotFound() } }
    }
}
