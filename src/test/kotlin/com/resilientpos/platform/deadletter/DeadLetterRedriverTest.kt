package com.resilientpos.platform.deadletter

import com.resilientpos.contract.EventContracts
import com.resilientpos.contract.OrderPaid
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.platform.BaseEntity
import com.resilientpos.platform.DependencyUnavailableException
import com.resilientpos.platform.Tx
import com.resilientpos.platform.TxRunner
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.SQLException
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willAnswer
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaProducerException
import org.springframework.kafka.support.SendResult
import org.springframework.transaction.CannotCreateTransactionException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import org.apache.kafka.common.errors.TimeoutException as KafkaTimeoutException

/**
 * 재투입의 경합·실패 경로. 실제 브로커와 DB로는 같은 좌표의 동시 커밋과 ack 시간 초과를 재현하기 어려워 reader·Kafka·저장소만 바꾼다.
 * 정상 경로와 기록 확인, HTTP 상태는 `DeadLetterRedriveIntegrationTest`가 실제 브로커와 DB로 본다.
 */
@ExtendWith(OutputCaptureExtension::class)
class DeadLetterRedriverTest {

    private val reader = mock(DeadLetterReader::class.java)

    @Suppress("UNCHECKED_CAST") // Mockito는 제네릭 인터페이스의 타입 인자를 모른다.
    private val kafka = mock(KafkaOperations::class.java) as KafkaOperations<Any, Any>
    private val redrives = mock(DeadLetterRedriveJpaRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val ids = object : IdentifierGenerator {
        override fun next() = ID
    }
    private val redriver = DeadLetterRedriver(
        reader, DeadLetterEventTypes(listOf(TOPIC, "payment.approved")), JSON_MAPPER, kafka, redrives, ids, meterRegistry, listOf(TOPIC, "payment.approved"),
    )

    private val record = dltRecord(VALUE)

    @BeforeTest
    fun initTx() = Tx.initialize(TxRunner())

    private fun redrive(reason: String = REASON) = redriver.redrive(DLT, 0, DLT_OFFSET, reason, OPERATOR)

    private fun redriven() = meterRegistry.find(DeadLetterRedriver.REDRIVEN_METRIC).tag("topic", TOPIC).counter()?.count()

    /** 전송했는데 기록이 남지 않은 재투입의 WARN 감사 줄. */
    private fun unrecorded(output: CapturedOutput) = output.all.lines().filter { "action=DEAD_LETTER_REDRIVE_UNRECORDED" in it }

    private fun assertUnrecordedLine(output: CapturedOutput, expected: String) {
        val line = unrecorded(output).single()
        assertTrue(line.endsWith(expected), line)
        assertTrue(" WARN " in line, line)
        assertFalse(REASON in output.all, "사유 원문이 로그에 남았다")
    }

    private fun acked(partition: Int, offset: Long): CompletableFuture<SendResult<Any, Any>> =
        CompletableFuture.completedFuture(SendResult(ProducerRecord(TOPIC, KEY, VALUE), RecordMetadata(TopicPartition(TOPIC, partition), offset, 0, 0, 0, 0)))

    @Test
    fun `기동하면 설정된 토픽마다 재투입 counter를 0으로 등록한다`() {
        assertEquals(0.0, redriven())
        assertEquals(0.0, meterRegistry.find(DeadLetterRedriver.REDRIVEN_METRIC).tag("topic", "payment.approved").counter()?.count())
    }

    @Test
    fun `앞뒤 공백을 지운 사유가 500자면 받고 지운 값을 같은 key와 값을 보낸 자리와 함께 기록한다`() {
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(acked(0, 41))
        willAnswer { managedCopy(it.arguments[0] as DeadLetterRedriveEntity, REDRIVEN_AT) }.given(redrives).save(any())

        val response = redrive("  " + "가".repeat(500) + "\n")

        assertEquals(DeadLetterRedriveResponse(DLT, 0, DLT_OFFSET, TOPIC, 0, 41, OPERATOR, REDRIVEN_AT), response)
        @Suppress("UNCHECKED_CAST") // 제네릭 클래스 리터럴이 없다.
        val sent = ArgumentCaptor.forClass(ProducerRecord::class.java) as ArgumentCaptor<ProducerRecord<Any, Any>>
        verify(kafka).send(sent.capture())
        assertEquals(TOPIC, sent.value.topic())
        assertEquals(null, sent.value.partition())
        assertEquals(KEY, sent.value.key())
        assertContentEquals(VALUE, sent.value.value() as ByteArray)
        assertEquals(0, sent.value.headers().toArray().size)
        val saved = ArgumentCaptor.forClass(DeadLetterRedriveEntity::class.java)
        verify(redrives).save(saved.capture())
        assertEquals("가".repeat(500), saved.value.reason)
        assertEquals(1.0, redriven())
    }

    @Test
    fun `빈 사유 500자 초과 NUL 문자가 든 사유는 INVALID_REDRIVE_REASON이고 읽지도 보내지도 기록하지도 않는다`() {
        listOf("", " \t\n ", "가".repeat(501), "원인\u0000수정").forEach { reason ->
            val e = assertFailsWith<DomainException>(reason) { redrive(reason) }
            assertEquals(DeadLetterRedriver.INVALID_REDRIVE_REASON, e.code, reason)
            assertEquals(DomainErrorKind.INVALID, e.kind, reason)
        }

        verifyNoInteractions(reader, kafka, redrives)
    }

    @Test
    fun `DLT 위치가 틀리면 기록을 조회하지 않고 그 400을 그대로 던진다`() {
        val invalid = DomainException(DeadLetterReader.INVALID_DEAD_LETTER_POSITION, "설정된 DLT 토픽이 아닙니다.")
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willThrow(invalid)

        assertSame(invalid, assertFailsWith<DomainException> { redrive() })
        verifyNoInteractions(redrives, kafka)
    }

    @Test
    fun `원 토픽의 이벤트 타입으로 읽지 못하면 DEAD_LETTER_NOT_READABLE이고 값 내용 없이 보내지도 기록하지도 않는다`() {
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(acked(0, 41))
        val newer = (JSON_MAPPER.readTree(VALUE) as ObjectNode).put("schemaVersion", OrderPaid.SCHEMA_VERSION + 1).toString().toByteArray()
        val unreadable = listOf(newer, "garbage-4521".toByteArray(), null)

        val messages = unreadable.map { value ->
            given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(dltRecord(value))
            val e = assertFailsWith<DomainException>(value?.decodeToString()) { redrive() }
            assertEquals(DeadLetterRedriver.DEAD_LETTER_NOT_READABLE, e.code)
            assertEquals(DomainErrorKind.UNPROCESSABLE, e.kind)
            e.message
        }

        assertEquals("지금 코드가 읽지 못하는 메시지입니다: UNSUPPORTED_EVENT_SCHEMA_VERSION", messages[0])
        assertTrue(messages[1].startsWith("지금 코드가 읽지 못하는 메시지입니다: ") && "garbage" !in messages[1], messages[1])
        assertEquals("지금 코드가 읽지 못하는 메시지입니다: 값이 없습니다", messages[2])
        verifyNoInteractions(kafka)
        verify(redrives, never()).save(any())
        assertEquals(0.0, redriven())
    }

    @Test
    fun `그 offset에 레코드가 없으면 DEAD_LETTER_NOT_FOUND이고 보내지도 기록하지도 않는다`() {
        val e = assertFailsWith<DomainException> { redrive() }

        assertEquals(DeadLetterRedriver.DEAD_LETTER_NOT_FOUND, e.code)
        assertEquals(DomainErrorKind.NOT_FOUND, e.kind)
        verifyNoInteractions(kafka)
        verify(redrives, never()).save(any())
    }

    @Test
    fun `기록 저장이 같은 레코드의 unique에 걸리면 이긴 쪽 기록을 돌려주고 counter와 재투입 감사 줄 대신 넣은 자리와 함께 DUPLICATE WARN 줄을 남긴다`(output: CapturedOutput) {
        val winner = stamp(DeadLetterRedriveEntity.of(UUID.randomUUID(), DLT, 0, DLT_OFFSET, DLT_TIMESTAMP, 0, 40, "operator-subject-2", "먼저 온 사유"), REDRIVEN_AT)
        given(redrives.findByDltTopicAndDltPartitionAndDltOffsetAndDltTimestamp(DLT, 0, DLT_OFFSET, DLT_TIMESTAMP)).willReturn(null, winner)
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(acked(0, 41))
        willThrow(
            DataIntegrityViolationException(
                "could not execute statement",
                SQLException("ERROR: duplicate key value violates unique constraint \"ux_dead_letter_redrive_source\"", "23505"),
            ),
        ).given(redrives).save(any())

        val response = redrive()

        assertEquals(DeadLetterRedriveResponse(DLT, 0, DLT_OFFSET, TOPIC, 0, 40, "operator-subject-2", REDRIVEN_AT), response)
        assertEquals(0.0, redriven())
        assertFalse("action=DEAD_LETTER_REDRIVEN" in output.all)
        assertUnrecordedLine(output, "audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=$OPERATOR dltTopic=$DLT dltPartition=0 dltOffset=$DLT_OFFSET topic=$TOPIC partition=0 offset=41 cause=DUPLICATE")
    }

    @Test
    fun `다른 제약의 위반은 번역하지 않고 그대로 던지며 넣은 자리와 함께 RECORD_FAILED WARN 줄을 남긴다`(output: CapturedOutput) {
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(acked(0, 41))
        val other = DataIntegrityViolationException("could not execute statement", SQLException("ERROR: duplicate key value violates unique constraint \"pk_other\"", "23505"))
        willThrow(other).given(redrives).save(any())

        assertSame(other, assertFailsWith<DataIntegrityViolationException> { redrive() })
        assertEquals(0.0, redriven())
        assertUnrecordedLine(output, "audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=$OPERATOR dltTopic=$DLT dltPartition=0 dltOffset=$DLT_OFFSET topic=$TOPIC partition=0 offset=41 cause=RECORD_FAILED")
    }

    @Test
    fun `전송 뒤 DB에 닿지 못해 기록하지 못하면 그 예외를 그대로 던지고 넣은 자리와 함께 RECORD_FAILED WARN 줄을 남긴다`(output: CapturedOutput) {
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(acked(0, 41))
        val down = CannotCreateTransactionException("Could not open JPA EntityManager for transaction")
        willThrow(down).given(redrives).save(any())

        assertSame(down, assertFailsWith<CannotCreateTransactionException> { redrive() })
        assertEquals(0.0, redriven())
        assertFalse("action=DEAD_LETTER_REDRIVEN" in output.all)
        assertUnrecordedLine(output, "audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=$OPERATOR dltTopic=$DLT dltPartition=0 dltOffset=$DLT_OFFSET topic=$TOPIC partition=0 offset=41 cause=RECORD_FAILED")
    }

    @Test
    fun `전송 ack가 10초 안에 오지 않으면 DependencyUnavailableException이고 기록하지 않으며 SEND_UNCONFIRMED WARN 줄을 남긴다`(output: CapturedOutput) {
        var waitedMillis = -1L
        val noAck = object : CompletableFuture<SendResult<Any, Any>>() {
            override fun get(timeout: Long, unit: TimeUnit): SendResult<Any, Any> {
                waitedMillis = unit.toMillis(timeout)
                throw TimeoutException()
            }
        }
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(noAck)

        val e = assertFailsWith<DependencyUnavailableException> { redrive() }

        assertIs<TimeoutException>(e.cause)
        assertEquals(10_000L, waitedMillis)
        verify(redrives, never()).save(any())
        assertEquals(0.0, redriven())
        assertUnrecordedLine(output, "audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=$OPERATOR dltTopic=$DLT dltPartition=0 dltOffset=$DLT_OFFSET cause=SEND_UNCONFIRMED")
    }

    @Test
    fun `ack를 기다리다 인터럽트되면 인터럽트 표시를 되살려 그 예외를 그대로 던지고 기록하지 않는다`() {
        val interrupted = InterruptedException("sleep interrupted")
        val waiting = object : CompletableFuture<SendResult<Any, Any>>() {
            override fun get(timeout: Long, unit: TimeUnit): SendResult<Any, Any> = throw interrupted
        }
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(kafka.send(any<ProducerRecord<Any, Any>>())).willReturn(waiting)

        val thrown = runCatching { redrive() }.exceptionOrNull()
        // 다음 테스트가 인터럽트된 스레드에서 돌지 않게 단언보다 먼저 읽어 지운다.
        val flagRestored = Thread.interrupted()

        assertSame(interrupted, thrown)
        assertTrue(flagRestored, "인터럽트 표시가 지워진 채 던졌다")
        verify(redrives, never()).save(any())
    }

    @Test
    fun `전송이 Kafka 시간 초과로 실패하면 DependencyUnavailableException이고 다른 Kafka 실패는 바꾸지 않는다`(output: CapturedOutput) {
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        val timeout = KafkaTimeoutException("Topic order.paid not present in metadata after 60000 ms.")
        // 메타데이터를 기다리다 실패하면 send가 곧바로 던지고, ack 전에 만료되면 future가 실패한다(spring-kafka 4.1.1 KafkaTemplate.doSend).
        given(kafka.send(any<ProducerRecord<Any, Any>>()))
            .willThrow(KafkaException("Send failed", timeout))
            .willReturn(CompletableFuture.failedFuture(KafkaProducerException(ProducerRecord(TOPIC, KEY, VALUE), "Failed to send", timeout)))
            .willReturn(CompletableFuture.failedFuture(KafkaProducerException(ProducerRecord(TOPIC, KEY, VALUE), "Failed to send", TopicAuthorizationException(TOPIC))))

        repeat(2) { assertFailsWith<DependencyUnavailableException> { redrive() } }
        val other = assertFailsWith<Exception> { redrive() }

        assertFalse(other is DependencyUnavailableException, "권한 실패가 503으로 바뀌었다: $other")
        verify(redrives, never()).save(any())
        assertEquals(listOf("SEND_UNCONFIRMED", "SEND_UNCONFIRMED"), unrecorded(output).map { it.substringAfter("cause=") })
    }

    private companion object {
        const val TOPIC = "order.paid"
        const val DLT = "order.paid.DLT"
        const val DLT_OFFSET = 7L
        const val KEY = "018f6b1a-0000-7000-8000-0000000000e1"
        const val OPERATOR = "operator-subject-1"
        const val REASON = "원인 수정 후 재투입"
        val ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000f1")
        val JSON_MAPPER: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        /** 지금 코드가 읽는 `OrderPaid` 값. 읽기 검사를 지나야 전송·기록까지 간다. */
        val VALUE: ByteArray = JSON_MAPPER.writeValueAsBytes(EventContracts.all.single { it.type == OrderPaid::class.java }.sample)
        val REDRIVEN_AT: Instant = Instant.parse("2026-10-21T02:00:00.123456Z")
        val DLT_TIMESTAMP: Instant = Instant.parse("2026-10-21T01:00:00.123Z")

        /** DLT 레코드. timestamp는 recoverer가 보낼 때 producer가 찍은 값이고 재투입 기록의 멱등 키에 들어간다. */
        fun dltRecord(value: ByteArray?) = ConsumerRecord<String, ByteArray>(
            DLT, 0, DLT_OFFSET, DLT_TIMESTAMP.toEpochMilli(), TimestampType.CREATE_TIME, 0, 0, KEY, value, RecordHeaders(), Optional.empty(),
        )

        /** Hibernate가 넣거나 읽어 올 때 채우는 생성 시각을 채운다. 컨텍스트 없는 단위 테스트라 Hibernate가 없다. */
        /** `merge`처럼 넘긴 객체가 아니라 생성 시각이 채워진 다른 인스턴스를 돌려준다. 응답이 `save`의 반환값으로 만들어지는지 가린다. */
        fun managedCopy(entity: DeadLetterRedriveEntity, at: Instant): DeadLetterRedriveEntity = stamp(with(entity) { DeadLetterRedriveEntity.of(id, dltTopic, dltPartition, dltOffset, dltTimestamp, targetPartition, targetOffset, operatorSubject, reason) }, at)

        fun stamp(entity: DeadLetterRedriveEntity, at: Instant): DeadLetterRedriveEntity = entity.also {
            BaseEntity::class.java.getDeclaredField("createdAt").apply { isAccessible = true }.set(it, at)
        }
    }
}
