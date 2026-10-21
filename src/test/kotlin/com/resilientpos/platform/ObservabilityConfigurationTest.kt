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
        // 발행 기록 metric의 신선도는 행이 없어도 늘 나간다. 알림 규칙이 쓰는 Prometheus 이름을 고정한다.
        val refreshed = body.lines().firstOrNull { it.startsWith("event_publication_incomplete_refreshed_seconds{") }
        assertNotNull(refreshed, "event_publication_incomplete_refreshed_seconds가 없다")
        assertContains(refreshed, """application="ResilientPOS"""")
    }

    @Test
    fun `prometheus 엔드포인트가 Kafka consumer metric과 토픽별 DLT 개수를 돌려준다`() {
        val body = mockMvc.get("/actuator/prometheus").andReturn().response.contentAsString

        val consumerMetric = body.lines().firstOrNull { it.startsWith("kafka_consumer_") && !it.startsWith("#") }
        assertNotNull(consumerMetric, "Kafka consumer metric이 없다")
        assertContains(consumerMetric, """application="ResilientPOS"""")
        // DLT로 간 적이 없어도 0으로 나가야 increase()가 첫 증가를 잡는다. 알림 규칙이 쓰는 이름과 태그를 고정한다.
        listOf("order.payment-requested", "payment.attempt-created", "payment.approved", "order.paid").forEach { topic ->
            val deadLetter = body.lines().firstOrNull { it.startsWith("event_kafka_dead_letter_total{") && it.contains("""topic="$topic"""") }
            assertNotNull(deadLetter, "$topic 의 event_kafka_dead_letter_total이 없다")
            assertContains(deadLetter, """application="ResilientPOS"""")
            // DLT 전송 실패도 기동할 때 0으로 나가 알림 규칙과 이름 대조가 이 이름을 확인할 수 있다.
            assertNotNull(
                body.lines().firstOrNull { it.startsWith("event_kafka_dead_letter_failed_total{") && it.contains("""topic="$topic"""") },
                "$topic 의 event_kafka_dead_letter_failed_total이 없다",
            )
        }
    }

    @Test
    fun `health와 prometheus 외의 Actuator 엔드포인트는 열지 않는다`() {
        mockMvc.get("/actuator/env").andExpect { status { isNotFound() } }
    }
}
