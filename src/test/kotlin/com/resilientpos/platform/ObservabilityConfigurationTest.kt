package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
        // runbook이 원인을 가르는 데 쓰는 이름과 태그를 고정한다.
        listOf("order.payment-requested", "payment.attempt-created", "payment.approved", "order.paid", "payment.reconciliation-requested").forEach { topic ->
            val deadLetter = body.lines().firstOrNull { it.startsWith("event_kafka_dead_letter_total{") && it.contains("""topic="$topic"""") }
            assertNotNull(deadLetter, "$topic 의 event_kafka_dead_letter_total이 없다")
            assertContains(deadLetter, """application="ResilientPOS"""")
            // DLT 전송 실패도 기동할 때 0으로 나가 알림 규칙과 이름 대조가 이 이름을 확인할 수 있다.
            assertNotNull(
                body.lines().firstOrNull { it.startsWith("event_kafka_dead_letter_failed_total{") && it.contains("""topic="$topic"""") },
                "$topic 의 event_kafka_dead_letter_failed_total이 없다",
            )
            // 알림이 보는 미처리 수는 첫 갱신 전 NaN이어도 토픽마다 나간다.
            val unhandled = body.lines().firstOrNull { it.startsWith("event_kafka_dead_letter_unhandled{") && it.contains("""topic="$topic"""") }
            assertNotNull(unhandled, "$topic 의 event_kafka_dead_letter_unhandled가 없다")
            assertContains(unhandled, """application="ResilientPOS"""")
        }
    }

    @Test
    fun `health와 prometheus 외의 Actuator 엔드포인트는 열지 않는다`() {
        mockMvc.get("/actuator/env").andExpect { status { isNotFound() } }
    }

    /**
     * 코드가 metric 이름을 바꾸고 규칙을 고치지 않으면 규칙이 조용히 아무것도 보지 못한다. promtool 테스트(`AlertRulesTest`)는 가짜 series로 돌아 이것을 잡지 못한다.
     * 규칙의 selector(`이름{`)를 모두 뽑아 앱 출력과 대조한다. 요청 metric처럼 첫 요청 뒤 생기는 이름이 있어 잠깐 기다린다.
     */
    @Test
    fun `알림 규칙이 쓰는 metric 이름이 모두 나간다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        val ruleMetrics = RULE_METRIC.findAll(Path.of("docker/prometheus/alerts.yaml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(ruleMetrics.containsAll(CREATED_ON_FIRST_EVENT.keys), "규칙이 더 쓰지 않는 예외가 있다: ${CREATED_ON_FIRST_EVENT.keys - ruleMetrics}")

        var missing = emptySet<String>()
        awaitUntil("알림 규칙의 metric이 모두 나간다", { " 없는 이름: $missing" }) {
            val exposed = mockMvc.get("/actuator/prometheus").andReturn().response.contentAsString.lines()
                .filterNot { it.startsWith("#") || it.isBlank() }
                .map { it.substringBefore('{').substringBefore(' ') }
                .toSet()
            missing = ruleMetrics - CREATED_ON_FIRST_EVENT.keys - exposed
            missing.isEmpty()
        }
    }

    companion object {
        private val RULE_METRIC = Regex("""([a-z_][a-z0-9_]*)\{""")

        /** 처음 일어날 때 생겨 기동 직후에는 없는 이름과 그 이유. 규칙에서 빠지면 위 테스트가 이 목록도 고치라고 실패한다. */
        private val CREATED_ON_FIRST_EVENT = mapOf(
            "lock_fail_open_total" to "처음 fail-open될 때 생긴다. platform이 업무 모듈의 키를 모른다",
            "kafka_consumer_fetch_manager_records_lag" to "Kafka 클라이언트가 파티션의 첫 fetch 뒤 만들고 Micrometer KafkaMetrics가 60초마다 새 metric을 묶는다",
        )
    }
}
