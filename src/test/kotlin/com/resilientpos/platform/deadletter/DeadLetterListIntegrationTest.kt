package com.resilientpos.platform.deadletter

import com.jayway.jsonpath.JsonPath
import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainException
import com.resilientpos.platform.OperatorTokens
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get

private const val DLT = "order.paid.DLT"
private const val ORIGINAL_OFFSET = 42L

/**
 * DLT 목록을 실제 브로커와 운영자 토큰(운영과 같은 issuer decoder)으로 읽는다. Docker가 필요하다.
 * `AdminSecurityIntegrationTest`와 같은 구성이라 컨텍스트를 함께 쓴다.
 * 테스트 Kafka는 partition이 하나이고 다른 테스트도 DLT에 레코드를 넣으므로, 이 테스트가 넣은 레코드의 offset을 기준으로 단언한다.
 */
@Import(OperatorTokens.IdpStub::class)
class DeadLetterListIntegrationTest(
    private val mockMvc: MockMvc,
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
) : PostgresIntegrationTest() {

    private fun list(vararg params: Pair<String, String>): ResultActionsDsl =
        mockMvc.get("/admin/v1/dead-letters") {
            header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer())
            params.forEach { (name, value) -> param(name, value) }
        }

    /**
     * consumer 실패처럼 `DeadLetterPublishingRecoverer`가 헤더(원 위치·예외·스택 트레이스)를 붙여 넣는다.
     * 리스너 예외는 컨테이너가 `ListenerExecutionFailedException`으로 감싸 넘기고, recoverer는 그 포장만 벗긴 예외를 원인 헤더에 쓴다.
     */
    private fun publishFailure(key: String, value: String): Long {
        val failed = ConsumerRecord<Any, Any>("order.paid", 0, ORIGINAL_OFFSET, key, value.toByteArray())
        DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ -> TopicPartition(DLT, 0) }
            .accept(failed, ListenerExecutionFailedException("Listener failed", DomainException("FULFILLMENT_TEST", "이미 처리된 주문입니다")))
        return KafkaTestSupport.records(DLT).single { it.key() == key }.offset()
    }

    private fun send(key: String, value: String, vararg headers: Pair<String, String>): Long {
        val record = ProducerRecord<Any, Any>(
            DLT, 0, TIMESTAMP.toEpochMilli(), key, value.toByteArray(), headers.map { (name, text) -> RecordHeader(name, text.toByteArray()) },
        )
        return kafkaTemplate.send(record).get(5, TimeUnit.SECONDS).recordMetadata.offset()
    }

    @Test
    fun `설정된 DLT의 fromOffset부터 limit건을 원 위치 예외 schemaVersion과 함께 돌려주고 없는 헤더는 null이며 스택 트레이스는 싣지 않는다`() {
        val publishedAfter = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val failedKey = UUID.randomUUID().toString()
        val failed = publishFailure(failedKey, """{"schemaVersion":1,"orderId":"$failedKey"}""")
        val bareKey = UUID.randomUUID().toString()
        val bare = send(bareKey, "not-json", KafkaHeaders.DLT_EXCEPTION_FQCN to "java.lang.IllegalStateException")
        val unversioned = send(UUID.randomUUID().toString(), """{"orderId":"$bareKey"}""")

        val body = list("topic" to DLT, "partition" to "0", "fromOffset" to "$failed", "limit" to "2")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.length()") { value(2) }
                jsonPath("$.data[0].partition") { value(0) }
                jsonPath("$.data[0].offset") { value(failed) }
                jsonPath("$.data[0].key") { value(failedKey) }
                jsonPath("$.data[0].value") { value("""{"schemaVersion":1,"orderId":"$failedKey"}""") }
                jsonPath("$.data[0].originalTopic") { value("order.paid") }
                jsonPath("$.data[0].originalPartition") { value(0) }
                jsonPath("$.data[0].originalOffset") { value(ORIGINAL_OFFSET) }
                jsonPath("$.data[0].exception") { value("com.resilientpos.foundation.DomainException") }
                jsonPath("$.data[0].exceptionMessage") { value("Listener failed; 이미 처리된 주문입니다") }
                jsonPath("$.data[0].schemaVersion") { value(1) }
                jsonPath("$.data[1].offset") { value(bare) }
                jsonPath("$.data[1].timestamp") { value(TIMESTAMP.toString()) }
                jsonPath("$.data[1].key") { value(bareKey) }
                jsonPath("$.data[1].value") { value("not-json") }
                jsonPath("$.data[1].originalTopic") { value(null) }
                jsonPath("$.data[1].originalPartition") { value(null) }
                jsonPath("$.data[1].originalOffset") { value(null) }
                jsonPath("$.data[1].exception") { value("java.lang.IllegalStateException") }
                jsonPath("$.data[1].exceptionMessage") { value(null) }
                jsonPath("$.data[1].schemaVersion") { value(null) }
            }.andReturn().response.contentAsString

        val first: Map<String, Any?> = JsonPath.read(body, "$.data[0]")
        assertEquals(ITEM_FIELDS, first.keys)
        val timestamp = Instant.parse(first["timestamp"] as String)
        assertTrue(timestamp >= publishedAfter && timestamp <= Instant.now(), "recoverer가 보낸 시각: $timestamp")

        list("topic" to DLT, "partition" to "0", "fromOffset" to "$unversioned", "limit" to "1").andExpect {
            status { isOk() }
            jsonPath("$.data[0].offset") { value(unversioned) }
            jsonPath("$.data[0].schemaVersion") { value(null) }
        }
    }

    @Test
    fun `fromOffset이 partition 끝 이상이면 빈 목록이다`() {
        val last = send(UUID.randomUUID().toString(), "{}")

        listOf(last + 1, Long.MAX_VALUE).forEach { from ->
            list("topic" to DLT, "partition" to "0", "fromOffset" to "$from").andExpect {
                status { isOk() }
                jsonPath("$.data.length()") { value(0) }
            }
        }
    }

    @Test
    fun `설정에 없는 토픽 없는 partition 음수 offset 1에서 50 밖의 limit은 400 INVALID_DEAD_LETTER_POSITION이다`() {
        val cases = mapOf(
            "원 토픽" to listOf("topic" to "order.paid", "partition" to "0"),
            "내부 토픽" to listOf("topic" to "__consumer_offsets", "partition" to "0"),
            "모르는 DLT" to listOf("topic" to "unknown.DLT", "partition" to "0"),
            "없는 partition" to listOf("topic" to DLT, "partition" to "1"),
            "음수 partition" to listOf("topic" to DLT, "partition" to "-1"),
            "음수 offset" to listOf("topic" to DLT, "partition" to "0", "fromOffset" to "-1"),
            "limit 0" to listOf("topic" to DLT, "partition" to "0", "limit" to "0"),
            "limit 51" to listOf("topic" to DLT, "partition" to "0", "limit" to "51"),
        )

        cases.forEach { (case, params) ->
            val response = list(*params.toTypedArray()).andReturn().response
            assertEquals(400, response.status, case)
            assertEquals("INVALID_DEAD_LETTER_POSITION", JsonPath.read(response.contentAsString, "$.error.errorCode"), case)
        }
    }

    /**
     * reader가 offset을 커밋할 수 있는 곳은 자기가 읽은 DLT partition뿐이다. 그래서 group이 새로 생기지 않고 어떤 group에도 DLT offset이 없음을 본다.
     * 원 토픽의 offset까지 전후로 비교하지 않는 이유: 앞 테스트가 남긴 이벤트를 앱 consumer가 이 사이에 처리하면 바뀔 수 있다.
     */
    @Test
    fun `목록을 읽어도 consumer group이 늘지 않고 어떤 group도 DLT에 offset을 커밋하지 않는다`() {
        val offset = send(UUID.randomUUID().toString(), "{}")
        val before = KafkaTestSupport.committedOffsets()

        list("topic" to DLT, "partition" to "0", "fromOffset" to "$offset").andExpect {
            status { isOk() }
            jsonPath("$.data[0].offset") { value(offset) }
        }

        val after = KafkaTestSupport.committedOffsets()
        assertEquals(before.keys, after.keys)
        assertEquals(emptyMap(), after.mapValues { (_, offsets) -> offsets.keys.filter { it.topic().endsWith(".DLT") } }.filterValues { it.isNotEmpty() })
    }

    private companion object {
        val TIMESTAMP: Instant = Instant.parse("2026-10-21T01:00:00Z")
        val ITEM_FIELDS = setOf(
            "partition", "offset", "timestamp", "key", "value", "originalTopic", "originalPartition", "originalOffset",
            "exception", "exceptionMessage", "schemaVersion", "redrive", "dismissal",
        )
    }
}
