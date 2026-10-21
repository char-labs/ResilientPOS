package com.resilientpos.platform.deadletter

import com.jayway.jsonpath.JsonPath
import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.contract.EventContracts
import com.resilientpos.contract.OrderPaid
import com.resilientpos.contract.PaymentApproved
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.platform.OperatorTokens
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import org.springframework.jdbc.core.queryForObject
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

private const val TOPIC = "order.paid"
private const val DLT = "order.paid.DLT"
private const val OLD_OPERATOR = "operator-before-recreate"
private const val WINNER_OPERATOR = "operator-winner"

/**
 * DLT 재투입을 실제 브로커·DB·운영자 토큰(운영과 같은 issuer decoder)으로 HTTP부터 fulfillment까지 잇는다. Docker가 필요하다.
 * `DeadLetterListIntegrationTest`와 같은 구성이라 컨텍스트를 함께 쓴다.
 * DLT 레코드는 consumer 실패처럼 `DeadLetterPublishingRecoverer`로 넣고(헤더 포함), 값은 지금 코드가 읽는 `OrderPaid` JSON이고, 읽기 검사는 그 JSON을 고친 값으로 본다.
 * 브로커와 DB를 다른 테스트와 함께 쓰므로 이 테스트가 만든 주문 key·DLT offset만 본다.
 */
@AutoConfigureMockMvc
@Import(OperatorTokens.IdpStub::class)
@ExtendWith(OutputCaptureExtension::class)
class DeadLetterRedriveIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val meterRegistry: MeterRegistry,
    @Autowired private val transaction: TransactionTemplate,
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

    /** [topic]의 consumer가 실패한 것처럼 그 DLT에 넣고 그 offset을 돌려준다. */
    private fun deadLetter(orderId: UUID, value: String = orderPaid(orderId), topic: String = TOPIC): Long {
        val failed = ConsumerRecord<Any, Any>(topic, 0, 42L, orderId.toString(), value.toByteArray())
        DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ -> TopicPartition("$topic.DLT", 0) }
            .accept(failed, ListenerExecutionFailedException("Listener failed", DomainException("FULFILLMENT_TEST", "재고 없음")))
        return KafkaTestSupport.records("$topic.DLT").single { it.key() == orderId.toString() }.offset()
    }

    private fun redrive(dltOffset: Long, reason: String, dlt: String = DLT): ResultActionsDsl =
        mockMvc.post("/admin/v1/dead-letters/$dlt/0/$dltOffset/redrive") {
            header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer())
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(mapOf("reason" to reason))
        }

    private fun ResultActionsDsl.data(): Map<String, Any?> = JsonPath.read(andReturn().response.contentAsString, "$.data")

    private fun redriven(): Double = meterRegistry.find(DeadLetterRedriver.REDRIVEN_METRIC).tag("topic", TOPIC).counter()?.count() ?: 0.0

    private fun rows(dltOffset: Long, dlt: String = DLT): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from t_dead_letter_redrive where dlt_topic = ? and dlt_partition = 0 and dlt_offset = ?", dlt, dltOffset)

    private fun auditLines(output: CapturedOutput, dltOffset: Long) =
        output.all.lines().filter { "action=DEAD_LETTER_REDRIVEN" in it && "dltOffset=$dltOffset " in it }

    private fun fulfillmentCount(orderId: UUID) =
        jdbcTemplate.queryForObject<Long>("select count(*) from t_fulfillment where order_id = ?", orderId)

    @Test
    fun `DLT로 간 order paid를 재투입하면 같은 key와 값이 order paid에 들어가 fulfillment가 생기고 기록 counter 감사 줄이 하나씩 남으며 감사 줄에 사유가 없다`(output: CapturedOutput) {
        val orderId = UUID.randomUUID()
        val value = orderPaid(orderId)
        val dltOffset = deadLetter(orderId, value)
        val before = redriven()

        val data = redrive(dltOffset, "  고객 문의 4521 원인 수정 뒤 재투입  ").andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SUCCESS") }
            jsonPath("$.data.dltTopic") { value(DLT) }
            jsonPath("$.data.dltPartition") { value(0) }
            jsonPath("$.data.dltOffset") { value(dltOffset) }
            jsonPath("$.data.topic") { value(TOPIC) }
            jsonPath("$.data.partition") { value(0) }
            jsonPath("$.data.operator") { value(OperatorTokens.SUBJECT) }
        }.data()

        val inserted = KafkaTestSupport.records(TOPIC, orderId).single()
        assertEquals(value, inserted.value())
        assertEquals((data["offset"] as Number).toLong(), inserted.offset())
        assertEquals(emptyList(), inserted.headers().map { it.key() }.filter { it.startsWith("kafka_dlt") })
        awaitUntil("재투입한 order.paid로 fulfillment가 생긴다") { fulfillmentCount(orderId) == 1L }

        val row = rows(dltOffset).single()
        assertEquals("고객 문의 4521 원인 수정 뒤 재투입", row["reason"])
        assertEquals(OperatorTokens.SUBJECT, row["operator_subject"])
        assertEquals(0, row["target_partition"])
        assertEquals(inserted.offset(), row["target_offset"])
        assertEquals((row["created_at"] as Timestamp).toInstant(), Instant.parse(data["redrivenAt"] as String))

        assertEquals(before + 1, redriven())
        val audit = auditLines(output, dltOffset).single()
        val expected = "audit action=DEAD_LETTER_REDRIVEN operator=${OperatorTokens.SUBJECT} dltTopic=$DLT dltPartition=0 dltOffset=$dltOffset " +
            "topic=$TOPIC partition=0 offset=${inserted.offset()}"
        assertTrue(audit.endsWith(expected), "감사 줄: $audit")
        assertFalse("고객 문의 4521" in output.all, "사유 원문이 로그에 남았다")
    }

    @Test
    fun `같은 좌표를 다시 재투입하면 처음 기록을 200으로 돌려주고 원 토픽 메시지 counter 감사 줄 기록이 늘지 않는다`(output: CapturedOutput) {
        val orderId = UUID.randomUUID()
        val dltOffset = deadLetter(orderId)
        val first = redrive(dltOffset, "처음 사유").andExpect { status { isOk() } }.data()
        val counted = redriven()

        val again = redrive(dltOffset, "다른 사유").andExpect { status { isOk() } }.data()

        assertEquals(first, again)
        assertEquals(1, KafkaTestSupport.records(TOPIC, orderId).size, "원 토픽에 다시 보냈다")
        assertEquals(counted, redriven())
        assertEquals(1, auditLines(output, dltOffset).size)
        assertEquals(listOf("처음 사유"), rows(dltOffset).map { it["reason"] })
    }

    @Test
    fun `재투입한 레코드는 목록의 redrive가 채워지고 다른 레코드는 null이다`() {
        val redrivenOffset = deadLetter(UUID.randomUUID())
        val otherOffset = deadLetter(UUID.randomUUID())
        val redrive = redrive(redrivenOffset, "목록 확인").andExpect { status { isOk() } }.data()

        val body = mockMvc.get("/admin/v1/dead-letters") {
            header(HttpHeaders.AUTHORIZATION, OperatorTokens.bearer())
            param("topic", DLT)
            param("partition", "0")
            param("fromOffset", "$redrivenOffset")
            param("limit", "${otherOffset - redrivenOffset + 1}")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        val items = JsonPath.read<List<Map<String, Any?>>>(body, "$.data").associateBy { (it["offset"] as Number).toLong() }
        assertEquals(
            mapOf("partition" to redrive["partition"], "offset" to redrive["offset"], "operator" to OperatorTokens.SUBJECT, "redrivenAt" to redrive["redrivenAt"]),
            items.getValue(redrivenOffset)["redrive"],
        )
        assertTrue(items.getValue(otherOffset).containsKey("redrive"))
        assertNull(items.getValue(otherOffset)["redrive"])
    }

    private fun dltTimestamp(dltOffset: Long): Instant = Instant.ofEpochMilli(KafkaTestSupport.records(DLT).single { it.offset() == dltOffset }.timestamp())

    /** API를 거치지 않은 재투입 기록. 넣은 자리는 원 토픽에 실제로 없는 값이다. */
    private fun insertRecord(dltOffset: Long, dltTimestamp: Instant, operator: String, targetOffset: Long) {
        jdbcTemplate.update(
            "insert into t_dead_letter_redrive (id, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, target_partition, target_offset, operator_subject, reason, created_at, updated_at) " +
                "values (?, ?, 0, ?, ?, 0, ?, ?, '다른 요청의 사유', now(), now())",
            UUID.randomUUID(), DLT, dltOffset, Timestamp.from(dltTimestamp), targetOffset, operator,
        )
    }

    /** DLT 토픽을 다시 만들어 offset이 0부터 재사용된 것처럼, 그 좌표에 레코드 시각만 다른 옛 기록을 넣는다. */
    private fun recordBeforeRecreate(dltOffset: Long) = insertRecord(dltOffset, dltTimestamp(dltOffset).minus(Duration.ofDays(30)), OLD_OPERATOR, 1)

    /**
     * [holder] backend에 막혀 아직 얻지 못한 잠금 수. 다른 테스트나 리스너의 잠금 대기는 세지 않는다(`OrderCreateRaceIntegrationTest`와 같다).
     * `pg_stat_activity`는 트랜잭션 안에서 처음 읽은 시점의 세션 목록에 고정되므로 매번 새로 읽는 `pg_locks`를 본다.
     */
    private fun locksBlockedBy(holder: Int): Long =
        jdbcTemplate.queryForObject<Long>("select count(*) from pg_locks where not granted and ? = any(pg_blocking_pids(pid))", holder) ?: 0L

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

    @Test
    fun `같은 좌표라도 레코드 시각이 다른 옛 기록은 목록의 redrive에 붙지 않는다`() {
        val dltOffset = deadLetter(UUID.randomUUID())
        recordBeforeRecreate(dltOffset)

        val item = listed(dltOffset)

        assertTrue(item.containsKey("redrive"))
        assertNull(item["redrive"], "옛 토픽의 기록이 붙었다: ${item["redrive"]}")
    }

    @Test
    fun `같은 좌표라도 레코드 시각이 다른 옛 기록이 있으면 새로 보내고 새 행을 남긴다`() {
        val orderId = UUID.randomUUID()
        val dltOffset = deadLetter(orderId)
        recordBeforeRecreate(dltOffset)

        val data = redrive(dltOffset, "토픽 재생성 뒤 재투입").andExpect {
            status { isOk() }
            jsonPath("$.data.operator") { value(OperatorTokens.SUBJECT) }
        }.data()

        assertEquals(1, KafkaTestSupport.records(TOPIC, orderId).size, "원 토픽에 보내지 않았다")
        assertEquals(setOf(OLD_OPERATOR, OperatorTokens.SUBJECT), rows(dltOffset).map { it["operator_subject"] }.toSet())
        assertEquals(data["offset"], listed(dltOffset).getValue("redrive").let { (it as Map<*, *>)["offset"] })
    }

    /**
     * 같은 레코드를 동시에 요청한 경합을 실제 DB로 만든다. 이긴 쪽 기록을 커밋 전에 붙들어 두면 진 쪽의 기록 확인(READ COMMITTED)은 그 행을 보지 못하고 보낸다.
     * 진 쪽 INSERT는 커밋 때 이긴 쪽 커밋을 기다리다 unique에 걸리고, 그 위반을 `Tx.masterTx` 밖에서 번역해 이긴 기록을 돌려준다.
     */
    @Test
    fun `기록 확인이 커밋 전인 같은 레코드의 기록을 못 보고 보냈으면 커밋의 unique 위반을 번역해 이긴 기록을 200으로 돌려주고 DUPLICATE WARN 줄을 남긴다`(output: CapturedOutput) {
        val orderId = UUID.randomUUID()
        val dltOffset = deadLetter(orderId)
        val timestamp = dltTimestamp(dltOffset)
        val executor = Executors.newSingleThreadExecutor()
        val response = try {
            checkNotNull(
                transaction.execute {
                    insertRecord(dltOffset, timestamp, WINNER_OPERATOR, 99)
                    // JpaTransactionManager가 DataSource에 이 트랜잭션의 연결을 묶어 두므로 JdbcTemplate도 같은 backend를 쓴다.
                    val holder = checkNotNull(jdbcTemplate.queryForObject<Int>("select pg_backend_pid()"))
                    val losing = executor.submit<MockHttpServletResponse> { redrive(dltOffset, "늦게 온 사유 7731").andReturn().response }
                    awaitUntil("재투입의 기록 INSERT가 이긴 기록의 커밋을 기다린다") { locksBlockedBy(holder) != 0L }
                    losing
                },
            ).get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertEquals(200, response.status, response.contentAsString)
        val data: Map<String, Any?> = JsonPath.read(response.contentAsString, "$.data")
        assertEquals(WINNER_OPERATOR, data["operator"])
        assertEquals(99L, (data["offset"] as Number).toLong())
        val sent = KafkaTestSupport.records(TOPIC, orderId).single()
        assertEquals(listOf(WINNER_OPERATOR), rows(dltOffset).map { it["operator_subject"] })
        val warn = output.all.lines().single { "action=DEAD_LETTER_REDRIVE_UNRECORDED" in it && "dltOffset=$dltOffset " in it }
        val expected = "audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=${OperatorTokens.SUBJECT} dltTopic=$DLT dltPartition=0 dltOffset=$dltOffset " +
            "topic=$TOPIC partition=0 offset=${sent.offset()} cause=DUPLICATE"
        assertTrue(warn.endsWith(expected), "감사 줄: $warn")
        assertTrue(" WARN " in warn, warn)
        assertEquals(emptyList(), auditLines(output, dltOffset))
        assertFalse("늦게 온 사유 7731" in output.all, "사유 원문이 로그에 남았다")
    }

    @Test
    fun `schemaVersion이 아는 것보다 큰 메시지와 모르는 enum 값이 든 메시지의 재투입은 422 DEAD_LETTER_NOT_READABLE이고 원 토픽에 메시지가 늘지 않으며 기록도 남지 않는다`(output: CapturedOutput) {
        val newerOrder = UUID.randomUUID()
        val newer = (jsonMapper.readTree(orderPaid(newerOrder)) as ObjectNode).put("schemaVersion", OrderPaid.SCHEMA_VERSION + 1).toString()
        val refundOrder = UUID.randomUUID()
        val approved = (EventContracts.all.single { it.type == PaymentApproved::class.java }.sample as PaymentApproved).copy(orderId = refundOrder)
        val unknownKind = (jsonMapper.readTree(jsonMapper.writeValueAsString(approved)) as ObjectNode).put("kind", "REFUND").toString()

        fun assertNotReadable(topic: String, orderId: UUID, value: String, cause: String) {
            val dltOffset = deadLetter(orderId, value, topic)

            redrive(dltOffset, "새 버전 배포 뒤 재투입", "$topic.DLT").andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.error.errorCode") { value("DEAD_LETTER_NOT_READABLE") }
                jsonPath("$.error.message") { value("지금 코드가 읽지 못하는 메시지입니다: $cause") }
            }

            assertEquals(emptyList(), KafkaTestSupport.records(topic, orderId), "$topic 에 보냈다")
            assertEquals(emptyList(), rows(dltOffset, "$topic.DLT"))
        }
        assertNotReadable(TOPIC, newerOrder, newer, "UNSUPPORTED_EVENT_SCHEMA_VERSION")
        assertNotReadable(PaymentApproved.TOPIC, refundOrder, unknownKind, "InvalidFormatException")

        assertFalse("REFUND" in output.all, "값 내용(Jackson 메시지의 입력 조각)이 로그에 남았다")
    }

    @Test
    fun `없는 offset은 404 DEAD_LETTER_NOT_FOUND이고 빈 사유 500자 초과 NUL 사유는 400 INVALID_REDRIVE_REASON이며 둘 다 보내지도 기록하지도 않는다`() {
        val orderId = UUID.randomUUID()
        val dltOffset = deadLetter(orderId)
        val missing = dltOffset + 1_000_000

        redrive(missing, "사유").andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("DEAD_LETTER_NOT_FOUND") }
        }
        listOf("   ", "가".repeat(501), "원인\u0000수정").forEach { reason ->
            val response = redrive(dltOffset, reason).andReturn().response
            assertEquals(400, response.status, reason)
            assertEquals("INVALID_REDRIVE_REASON", JsonPath.read(response.contentAsString, "$.error.errorCode"), reason)
        }

        assertEquals(emptyList(), rows(missing))
        assertEquals(emptyList(), rows(dltOffset))
        assertEquals(emptyList(), KafkaTestSupport.records(TOPIC, orderId))
    }
}
