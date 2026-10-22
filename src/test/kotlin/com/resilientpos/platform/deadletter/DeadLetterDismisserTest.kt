package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.platform.BaseEntity
import com.resilientpos.platform.Tx
import com.resilientpos.platform.TxRunner
import jakarta.persistence.EntityManager
import java.sql.SQLException
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
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

/**
 * 폐기의 사유 경계·기록 확인·경합 경로. 같은 레코드의 동시 커밋은 실제 DB로 재현하기 어려워 reader·저장소만 바꾼다.
 * 정상 경로와 HTTP 상태, 목록 연동은 `DeadLetterDismissIntegrationTest`가 실제 브로커와 DB로 본다.
 */
@ExtendWith(OutputCaptureExtension::class)
class DeadLetterDismisserTest {

    private val reader = mock(DeadLetterReader::class.java)
    private val dismissals = mock(DeadLetterDismissalJpaRepository::class.java)
    private val entityManager = mock(EntityManager::class.java)
    private val ids = object : IdentifierGenerator {
        override fun next() = ID
    }
    private val dismisser = DeadLetterDismisser(reader, dismissals, entityManager, ids)

    private val record = dltRecord()

    @BeforeTest
    fun initTx() = Tx.initialize(TxRunner())

    private fun dismiss(reason: String = REASON) = dismisser.dismiss(DLT, 0, DLT_OFFSET, reason, OPERATOR)

    private fun auditLines(output: CapturedOutput) = output.all.lines().filter { "action=DEAD_LETTER_DISMISSED" in it }

    @Test
    fun `앞뒤 공백을 지운 사유가 500자면 받고 지운 값을 기록해 처음 기록으로 응답하고 감사 INFO 한 줄에 사유가 없다`(output: CapturedOutput) {
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        willAnswer { stamp(it.arguments[0] as DeadLetterDismissalEntity, DISMISSED_AT); null }.given(entityManager).persist(any())
        val reason = "가".repeat(500)

        val response = dismiss("  $reason\n")

        assertEquals(DeadLetterDismissResponse(DLT, 0, DLT_OFFSET, OPERATOR, DISMISSED_AT), response)
        val saved = ArgumentCaptor.forClass(DeadLetterDismissalEntity::class.java)
        verify(entityManager).persist(saved.capture())
        assertEquals(reason, saved.value.reason)
        assertEquals(DLT_TIMESTAMP, saved.value.dltTimestamp)
        assertEquals(ID, saved.value.id)
        val audit = auditLines(output).single()
        assertTrue(audit.endsWith("audit action=DEAD_LETTER_DISMISSED operator=$OPERATOR dltTopic=$DLT dltPartition=0 dltOffset=$DLT_OFFSET"), audit)
        assertTrue(" INFO " in audit, audit)
        assertFalse(reason in output.all, "사유 원문이 로그에 남았다")
    }

    @Test
    fun `빈 사유 500자 초과 NUL 문자가 든 사유는 INVALID_DISMISS_REASON이고 DLT를 읽지도 기록하지도 않는다`() {
        listOf("", " \t\n ", "가".repeat(501), "원인\u0000수정").forEach { reason ->
            val e = assertFailsWith<DomainException>(reason) { dismiss(reason) }
            assertEquals("INVALID_DISMISS_REASON", e.code, reason)
            assertEquals(DomainErrorKind.INVALID, e.kind, reason)
        }

        verifyNoInteractions(reader, dismissals, entityManager)
    }

    @Test
    fun `DLT 위치가 틀리면 기록을 조회하지 않고 그 400을 그대로 던진다`() {
        val invalid = DomainException(DeadLetterReader.INVALID_DEAD_LETTER_POSITION, "설정된 DLT 토픽이 아닙니다.")
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willThrow(invalid)

        assertSame(invalid, assertFailsWith<DomainException> { dismiss() })
        verifyNoInteractions(dismissals, entityManager)
    }

    @Test
    fun `그 offset에 레코드가 없으면 DEAD_LETTER_NOT_FOUND이고 기록하지 않는다`() {
        val e = assertFailsWith<DomainException> { dismiss() }

        assertEquals(DeadLetterRedriver.DEAD_LETTER_NOT_FOUND, e.code)
        assertEquals(DomainErrorKind.NOT_FOUND, e.kind)
        verifyNoInteractions(dismissals, entityManager)
    }

    @Test
    fun `같은 레코드의 기록이 이미 있으면 persist 없이 처음 기록을 돌려주고 감사 줄을 남기지 않는다`(output: CapturedOutput) {
        val first = stamp(DeadLetterDismissalEntity.of(UUID.randomUUID(), DLT, 0, DLT_OFFSET, DLT_TIMESTAMP, "operator-subject-2", "먼저 온 사유"), DISMISSED_AT)
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        given(dismissals.findByDltTopicAndDltPartitionAndDltOffsetAndDltTimestamp(DLT, 0, DLT_OFFSET, DLT_TIMESTAMP)).willReturn(first)

        val response = dismiss("다른 사유")

        assertEquals(DeadLetterDismissResponse(DLT, 0, DLT_OFFSET, "operator-subject-2", DISMISSED_AT), response)
        verify(entityManager, never()).persist(any())
        assertEquals(emptyList(), auditLines(output))
    }

    @Test
    fun `기록 저장이 같은 레코드의 unique에 걸리면 이긴 쪽 기록을 돌려주고 감사 줄을 남기지 않는다`(output: CapturedOutput) {
        val winner = stamp(DeadLetterDismissalEntity.of(UUID.randomUUID(), DLT, 0, DLT_OFFSET, DLT_TIMESTAMP, "operator-subject-2", "먼저 온 사유"), DISMISSED_AT)
        given(dismissals.findByDltTopicAndDltPartitionAndDltOffsetAndDltTimestamp(DLT, 0, DLT_OFFSET, DLT_TIMESTAMP)).willReturn(null, winner)
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        willThrow(
            DataIntegrityViolationException(
                "could not execute statement",
                SQLException("ERROR: duplicate key value violates unique constraint \"ux_dead_letter_dismissal_source\"", "23505"),
            ),
        ).given(entityManager).persist(any())

        val response = dismiss()

        assertEquals(DeadLetterDismissResponse(DLT, 0, DLT_OFFSET, "operator-subject-2", DISMISSED_AT), response)
        assertEquals(emptyList(), auditLines(output))
    }

    @Test
    fun `다른 제약의 위반은 번역하지 않고 그대로 던지며 감사 줄을 남기지 않는다`(output: CapturedOutput) {
        given(reader.readRecord(DLT, 0, DLT_OFFSET)).willReturn(record)
        val other = DataIntegrityViolationException("could not execute statement", SQLException("ERROR: duplicate key value violates unique constraint \"pk_other\"", "23505"))
        willThrow(other).given(entityManager).persist(any())

        assertSame(other, assertFailsWith<DataIntegrityViolationException> { dismiss() })
        assertEquals(emptyList(), auditLines(output))
    }

    private companion object {
        const val DLT = "order.paid.DLT"
        const val DLT_OFFSET = 7L
        const val KEY = "018f6b1a-0000-7000-8000-0000000000e1"
        const val OPERATOR = "operator-subject-1"
        const val REASON = "재투입하지 않는 메시지"
        val ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000f2")
        val DISMISSED_AT: Instant = Instant.parse("2026-10-21T03:00:00.123456Z")
        val DLT_TIMESTAMP: Instant = Instant.parse("2026-10-21T01:00:00.123Z")

        /** 값은 읽지 못하는 바이트여도 된다. 폐기는 읽기 검사를 하지 않는다. */
        fun dltRecord() = ConsumerRecord<String, ByteArray>(
            DLT, 0, DLT_OFFSET, DLT_TIMESTAMP.toEpochMilli(), TimestampType.CREATE_TIME, 0, 0, KEY, "not-json".toByteArray(), RecordHeaders(), Optional.empty(),
        )

        /** Hibernate가 넣거나 읽어 올 때 채우는 생성 시각을 채운다. 컨텍스트 없는 단위 테스트라 Hibernate가 없다. */
        fun stamp(entity: DeadLetterDismissalEntity, at: Instant): DeadLetterDismissalEntity = entity.also {
            BaseEntity::class.java.getDeclaredField("createdAt").apply { isAccessible = true }.set(it, at)
        }
    }
}
