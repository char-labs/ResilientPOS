package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.terminal.RegisteredTerminal
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * checkout 요청 하나의 trace가 Modulith externalization과 Kafka를 지나 다음 consumer까지 이어지는지 본다. Docker가 필요하다.
 * 흐름은 checkout → `order.payment-requested` → `payment.attempt-created` → `payment.approved` → `order.paid`다.
 * 테스트는 trace export를 기본으로 끄므로 `@AutoConfigureTracing`으로 켜고, span은 in-memory exporter로 모은다(컨텍스트가 하나 더 뜬다).
 * Modulith 모듈 span(`모듈 > 메서드`)은 보지 않는다. Modulith가 모듈별 interceptor를 정적 캐시(`ModuleEntryInterceptor.CACHE`, 2.1.1)에 두어
 * JVM에서 먼저 뜬 다른 테스트 컨텍스트의 `ObservationRegistry`를 계속 쓰기 때문이다. 단독 실행에서만 보이므로 compose 실측으로 확인한다.
 */
@AutoConfigureMockMvc
@AutoConfigureTracing
@Import(TracePropagationIntegrationTest.SpanCapture::class)
@ExtendWith(OutputCaptureExtension::class)
class TracePropagationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val terminals: TerminalManagement,
    @Autowired private val spans: InMemorySpanExporter,
    @Autowired private val tracerProvider: SdkTracerProvider,
) : PostgresIntegrationTest() {

    @TestConfiguration(proxyBeanMethods = false)
    class SpanCapture {
        @Bean
        fun inMemorySpanExporter(): InMemorySpanExporter = InMemorySpanExporter.create()
    }

    /** 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다. */
    private fun createOrder(terminal: RegisteredTerminal): String {
        val body = mockMvc.post("/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", terminal.bearer)
            content = """
                { "storeId": "${terminal.storeId.value}", "terminalId": "${terminal.terminalId.value}", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                  "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 1 }] }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return JsonMapper.builder().build().readTree(body).path("data").path("orderId").asString()
    }

    private fun finished(): List<SpanData> {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS)
        return spans.finishedSpanItems
    }

    /** Spring Kafka의 observation convention은 전송 span에 `messaging.destination.name`, 소비 span에 `messaging.source.name`을 둔다. */
    private fun SpanData.topic(): String? =
        attributes.get(AttributeKey.stringKey(if (kind == SpanKind.CONSUMER) "messaging.source.name" else "messaging.destination.name"))

    @Test
    fun `checkout 요청의 trace가 Kafka 네 토픽의 전송과 소비로 이어지고 로그에 그 traceId가 남는다`(output: CapturedOutput) {
        val terminal = terminals.registerTerminal()
        val orderId = createOrder(terminal)

        mockMvc.post("/v1/orders/$orderId/checkout") { header("Authorization", terminal.bearer) }
            .andExpect { status { isOk() } }

        var traceId = ""
        awaitUntil("checkout 요청의 trace에 order.paid 소비 span이 생긴다", { "\nspans=" + finished().filter { it.kind != SpanKind.INTERNAL && it.kind != SpanKind.CLIENT }.map { "${it.kind} ${it.name} ${it.traceId}" } }) {
            val all = finished()
            val checkout = all.firstOrNull { it.kind == SpanKind.SERVER && it.name.contains("checkout") } ?: return@awaitUntil false
            traceId = checkout.traceId
            all.any { it.traceId == traceId && it.kind == SpanKind.CONSUMER && it.topic() == "order.paid" }
        }

        val trace = finished().filter { it.traceId == traceId }
        val topics = setOf("order.payment-requested", "payment.attempt-created", "payment.approved", "order.paid")
        assertEquals(topics, trace.filter { it.kind == SpanKind.PRODUCER }.mapNotNull { it.topic() }.toSet())
        assertEquals(topics, trace.filter { it.kind == SpanKind.CONSUMER }.mapNotNull { it.topic() }.toSet())
        assertContains(output.out, traceId)
    }
}
