package com.resilientpos.platform.deadletter

import com.jayway.jsonpath.JsonPath
import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.OrderPaid
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.platform.OperatorTokens
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

private const val TOPIC = "order.paid"
private const val DLT = "order.paid.DLT"
private const val OLD_OPERATOR = "operator-before-recreate"

/**
 * DLT 폐기를 실제 브로커·DB·운영자 토큰(운영과 같은 issuer decoder)으로 HTTP부터 기록·목록까지 잇는다. Docker가 필요하다.
 * `DeadLetterRedriveIntegrationTest`와 같은 구성이라 컨텍스트를 함께 쓴다. DLT 레코드는 consumer 실패처럼 `DeadLetterPublishingRecoverer`로 넣는다.
 * 브로커와 DB를 다른 테스트와 함께 쓰므로 이 테스트가 만든 주문 key·DLT offset만 본다.
 */
@AutoConfigureMockMvc
@Import(OperatorTokens.IdpStub::class)
@ExtendWith(OutputCaptureExtension::class)
class DeadLetterDismissIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private fun orderPaid(orderId: UUID): String = jsonMapper.writeValueAsString(
        OrderPaid(
            eventId = UUID.randomUUID(),
            occurredAt = Instant.now(),
            orderId = orderId,
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            checkoutNo = 1,
            amount = Money.of("4500", Currency.getInstance("KRW")),
        ),
    )

    /** consumer가 실패한 것처럼 DLT에 넣고 그 offset을 돌려준다. */
    private fun deadLetter(orderId: UUID, value: String = orderPaid(orderId)): Long {
        val failed = ConsumerRecord<Any, Any>(TOPIC, 0, 42L, orderId.toString(), value.toByteArray())
        DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ -> TopicPartition(DLT, 0) }
            .accept(failed, ListenerExecutionFailedException("Listener failed", DomainException("FULFILLMENT_TEST", "재고 없음")))
        return KafkaTestSupport.records(DLT).single { it.key() == orderId.toString() }.offset()
    }

    private fun dismiss(dltOffset: Long, reason: String): ResultActionsDsl =
        mockMvc.post("/admin/v1/dead-letters/$DLT/0/$dltOffset/dismiss") {
            header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer())
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(mapOf("reason" to reason))
        }

    private fun redrive(dltOffset: Long, reason: String): ResultActionsDsl =
        mockMvc.post("/admin/v1/dead-letters/$DLT/0/$dltOffset/redrive") {
            header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer())
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(mapOf("reason" to reason))
        }

    private fun ResultActionsDsl.data(): Map<String, Any?> = JsonPath.read(andReturn().response.contentAsString, "$.data")

    private fun rows(dltOffset: Long): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from t_dead_letter_dismissal where dlt_topic = ? and dlt_partition = 0 and dlt_offset = ?", DLT, dltOffset)

    private fun auditLines(output: CapturedOutput, dltOffset: Long) =
        output.all.lines().filter { "action=DEAD_LETTER_DISMISSED" in it && "dltOffset=$dltOffset" in it }

    private fun listed(dltOffset: Long): Map<String, Any?> {
        val body = mockMvc.get("/admin/v1/dead-letters") {
            header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer())
            param("topic", DLT)
            param("partition", "0")
            param("fromOffset", "$dltOffset")
            param("limit", "1")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return JsonPath.read<List<Map<String, Any?>>>(body, "$.data").single { (it["offset"] as Number).toLong() == dltOffset }
    }

    private fun dltTimestamp(dltOffset: Long): Instant = Instant.ofEpochMilli(KafkaTestSupport.records(DLT).single { it.offset() == dltOffset }.timestamp())

    @Test
    fun `폐기하면 200과 행 하나와 사유가 남고 감사 줄이 하나이며 감사 줄에 사유가 없고 목록의 dismissal이 채워진다`(output: CapturedOutput) {
        val dltOffset = deadLetter(UUID.randomUUID())

        val data = dismiss(dltOffset, "  고객 문의 4521 재투입하지 않음  ").andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SUCCESS") }
            jsonPath("$.data.dltTopic") { value(DLT) }
            jsonPath("$.data.dltPartition") { value(0) }
            jsonPath("$.data.dltOffset") { value(dltOffset) }
            jsonPath("$.data.operator") { value(OperatorTokens.SUBJECT) }
        }.data()

        val row = rows(dltOffset).single()
        assertEquals("고객 문의 4521 재투입하지 않음", row["reason"])
        assertEquals(OperatorTokens.SUBJECT, row["operator_subject"])
        assertEquals((row["created_at"] as Timestamp).toInstant(), Instant.parse(data["dismissedAt"] as String))
        val audit = auditLines(output, dltOffset).single()
        assertTrue(audit.endsWith("audit action=DEAD_LETTER_DISMISSED operator=${OperatorTokens.SUBJECT} dltTopic=$DLT dltPartition=0 dltOffset=$dltOffset"), "감사 줄: $audit")
        assertFalse("고객 문의 4521" in output.all, "사유 원문이 로그에 남았다")
        assertEquals(mapOf("operator" to OperatorTokens.SUBJECT, "dismissedAt" to data["dismissedAt"]), listed(dltOffset)["dismissal"])
    }

    @Test
    fun `같은 레코드를 다시 폐기하면 처음 기록을 200으로 돌려주고 행과 감사 줄이 늘지 않으며 사유를 바꾸지 않는다`(output: CapturedOutput) {
        val dltOffset = deadLetter(UUID.randomUUID())
        val first = dismiss(dltOffset, "처음 사유").andExpect { status { isOk() } }.data()

        val again = dismiss(dltOffset, "다른 사유").andExpect { status { isOk() } }.data()

        assertEquals(first, again)
        assertEquals(listOf("처음 사유"), rows(dltOffset).map { it["reason"] })
        assertEquals(1, auditLines(output, dltOffset).size)
    }

    @Test
    fun `지금 코드가 읽지 못하는 메시지도 폐기된다`() {
        val dltOffset = deadLetter(UUID.randomUUID(), "this is not json")

        dismiss(dltOffset, "깨진 메시지").andExpect { status { isOk() } }

        assertEquals(1, rows(dltOffset).size)
    }

    @Test
    fun `재투입한 레코드를 폐기해도 두 기록이 모두 남고 목록에 redrive와 dismissal이 함께 보인다`() {
        val dltOffset = deadLetter(UUID.randomUUID())
        redrive(dltOffset, "재투입").andExpect { status { isOk() } }

        dismiss(dltOffset, "그래도 폐기").andExpect { status { isOk() } }

        assertEquals(1, rows(dltOffset).size)
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from t_dead_letter_redrive where dlt_topic = ? and dlt_offset = ?", Long::class.java, DLT, dltOffset))
        val item = listed(dltOffset)
        assertEquals(OperatorTokens.SUBJECT, (item["redrive"] as Map<*, *>)["operator"])
        assertEquals(OperatorTokens.SUBJECT, (item["dismissal"] as Map<*, *>)["operator"])
    }

    @Test
    fun `폐기하지 않은 레코드는 목록의 dismissal이 null이다`() {
        val dltOffset = deadLetter(UUID.randomUUID())

        val item = listed(dltOffset)

        assertTrue(item.containsKey("dismissal"))
        assertNull(item["dismissal"])
    }

    @Test
    fun `같은 좌표라도 레코드 시각이 다른 옛 기록은 목록의 dismissal에 붙지 않는다`() {
        val dltOffset = deadLetter(UUID.randomUUID())
        // DLT 토픽을 다시 만들어 offset이 재사용된 것처럼, 그 좌표에 레코드 시각만 다른 옛 기록을 넣는다.
        jdbcTemplate.update(
            "insert into t_dead_letter_dismissal (id, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, operator_subject, reason, created_at, updated_at) " +
                "values (?, ?, 0, ?, ?, ?, '옛 토픽의 사유', now(), now())",
            UUID.randomUUID(), DLT, dltOffset, Timestamp.from(dltTimestamp(dltOffset).minus(Duration.ofDays(30))), OLD_OPERATOR,
        )

        val item = listed(dltOffset)

        assertTrue(item.containsKey("dismissal"))
        assertNull(item["dismissal"], "옛 토픽의 기록이 붙었다: ${item["dismissal"]}")
    }

    @Test
    fun `없는 offset은 404이고 빈 사유 500자 초과 NUL 사유는 400 INVALID_DISMISS_REASON이며 기록하지 않는다`() {
        val dltOffset = deadLetter(UUID.randomUUID())
        val missing = dltOffset + 1_000_000

        dismiss(missing, "사유").andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("DEAD_LETTER_NOT_FOUND") }
        }
        listOf("   ", "가".repeat(501), "원인\u0000수정").forEach { reason ->
            val response = dismiss(dltOffset, reason).andReturn().response
            assertEquals(400, response.status, reason)
            assertEquals("INVALID_DISMISS_REASON", JsonPath.read(response.contentAsString, "$.error.errorCode"), reason)
        }

        assertEquals(emptyList(), rows(missing))
        assertEquals(emptyList(), rows(dltOffset))
    }
}
