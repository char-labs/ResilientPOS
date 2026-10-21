package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.platform.DependencyUnavailableException
import com.resilientpos.platform.Tx
import com.resilientpos.platform.isUniqueViolationOf
import com.resilientpos.platform.logger
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.core.KafkaOperations
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import org.apache.kafka.common.errors.TimeoutException as KafkaTimeoutException

/**
 * 플랫폼 운영자의 DLT 재투입. 순서는 DLT 읽기 → 기록 확인 → 읽기 검사 → 원 토픽 전송 → 기록이다.
 * 1. DLT에서 레코드를 읽는다. 위치 검증(DB보다 먼저)과 시간 상한은 목록과 같고([DeadLetterReader]), 그 offset에 레코드가 없으면 [DEAD_LETTER_NOT_FOUND]다.
 * 2. 그 레코드(좌표와 레코드 시각)의 기록이 있으면 다시 보내지 않고 처음 기록을 돌려준다. 사유가 달라도 처음 기록을 바꾸지 않는다.
 *    시각까지 보는 이유는 DLT 토픽을 다시 만들면 offset이 재사용되기 때문이다. 그래서 재요청도 브로커를 읽고, 브로커 장애면 기록이 있어도 503이다.
 * 3. 값을 원 토픽(DLT 이름에서 `.DLT`를 뺀 것)의 이벤트 타입([DeadLetterEventTypes])으로 consumer와 같은 `JsonMapper` 빈으로 읽어 본다.
 *    버전 가드는 이벤트 `init`이라 생성자 호출로 함께 걸린다. 읽지 못하면 [DEAD_LETTER_NOT_READABLE]이고 보내지도 기록하지도 않는다.
 *    요청을 받은 pod의 코드로만 확인한다. 롤링 배포 중이면 옛 pod의 consumer가 읽지 못할 수 있어 모든 pod 배포는 운영자가 본다.
 * 4. 같은 key와 같은 값 바이트를 원 토픽에 보내고 ack를 [SEND_TIMEOUT]까지 기다린다. 트랜잭션 밖이다.
 *    템플릿의 직렬화기가 String·ByteArray라 다시 직렬화하지 않는다. partition은 정하지 않아 producer가 key로 고른다.
 *    DLT 헤더는 옮기지 않는다. consumer는 타입 헤더를 쓰지 않고, 옮기면 다시 실패했을 때 DLT 헤더가 겹친다.
 * 5. `Tx.masterTx`에서 기록을 넣는다. 같은 레코드를 동시에 요청해 둘 다 보냈으면 unique가 기록을 하나로 묶고 진 쪽은 이긴 쪽 기록을 돌려준다.
 *
 * 브로커에 닿지 못하면(읽기·전송 시간 초과) [DependencyUnavailableException](503)이고 기록하지 않는다. 시간 초과 뒤 전송이 실제로 됐을 수 있어
 * 다시 요청하면 한 번 더 보낸다. 전송 뒤 기록 커밋이 실패해도 같다. 두 경우 모두 consumer가 멱등이라 업무 결과는 하나다.
 * counter와 감사 INFO 한 줄은 처음 재투입했을 때만, 커밋 뒤에 남긴다. 사유 원문은 자유 입력이라 로그에 싣지 않는다(DB 기록이 정본이다).
 * 전송했는데 이 요청의 기록이 남지 않는 세 경로는 WARN 감사 줄(`DEAD_LETTER_REDRIVE_UNRECORDED`)이 행위자를 남긴다.
 * `cause`는 `DUPLICATE`(경합에 졌다), `RECORD_FAILED`(기록 커밋 실패, 500), `SEND_UNCONFIRMED`(ack 시간 초과, 실제 전송 여부 모름, 503)다. 앞의 둘은 넣은 자리도 싣는다.
 */
@Component
class DeadLetterRedriver(
    private val reader: DeadLetterReader,
    private val eventTypes: DeadLetterEventTypes,
    private val jsonMapper: JsonMapper,
    private val kafka: KafkaOperations<Any, Any>,
    private val redrives: DeadLetterRedriveJpaRepository,
    private val entityManager: EntityManager,
    private val identifierGenerator: IdentifierGenerator,
    private val meterRegistry: MeterRegistry,
    @Value("\${resilientpos.kafka.topics}") topics: List<String>,
) {
    init {
        // 재투입한 적이 없어도 0으로 나가야 알림의 increase()가 첫 증가를 잡는다(DLT counter와 같다).
        topics.forEach { meterRegistry.counter(REDRIVEN_METRIC, "topic", it) }
    }

    /** [operator]는 운영자 토큰의 `sub`다. 같은 레코드의 기록이 커밋에서 겹치면 여기서 가려야 하므로 바깥 트랜잭션 안에서 부르지 않는다. */
    fun redrive(topic: String, partition: Int, offset: Long, reason: String, operator: String): DeadLetterRedriveResponse {
        val trimmedReason = reason.trim()
        // NUL은 PostgreSQL varchar에 들어가지 못해 커밋에서 500이 된다(대조 case note와 같다).
        if (trimmedReason.isEmpty() || trimmedReason.length > MAX_REASON_LENGTH || NUL in trimmedReason) {
            throw DomainException(INVALID_REDRIVE_REASON, "사유는 앞뒤 공백을 지운 뒤 1~${MAX_REASON_LENGTH}자이고 NUL 문자가 없어야 합니다.")
        }
        val record = reader.readRecord(topic, partition, offset)
            ?: throw DomainException(DEAD_LETTER_NOT_FOUND, "그 offset에 DLT 레코드가 없습니다.", DomainErrorKind.NOT_FOUND)
        val dltTimestamp = Instant.ofEpochMilli(record.timestamp())
        recorded(topic, partition, offset, dltTimestamp)?.let { return DeadLetterRedriveResponse.from(it) }
        val originalTopic = DeadLetterReader.originalTopic(topic)
        requireReadable(originalTopic, record.value())

        fun warnUnrecorded(cause: String, sent: RecordMetadata? = null) = log.warn {
            "audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=$operator dltTopic=$topic dltPartition=$partition dltOffset=$offset " +
                sent?.let { "topic=${it.topic()} partition=${it.partition()} offset=${it.offset()} " }.orEmpty() + "cause=$cause"
        }
        val sent = try {
            send(ProducerRecord(originalTopic, record.key(), record.value()))
        } catch (e: DependencyUnavailableException) {
            warnUnrecorded("SEND_UNCONFIRMED")
            throw e
        }
        val redrive = DeadLetterRedriveEntity.of(
            identifierGenerator.next(), topic, partition, offset, dltTimestamp, sent.partition(), sent.offset(), operator, trimmedReason,
        )
        try {
            Tx.masterTx { entityManager.persist(redrive) }
        } catch (e: Exception) {
            // 이긴 기록을 읽기 전에 남긴다. 그 조회가 실패해도 이 요청이 보낸 사실과 행위자는 남는다.
            if (e !is DataIntegrityViolationException || !e.isUniqueViolationOf(SOURCE_CONSTRAINT)) {
                warnUnrecorded("RECORD_FAILED", sent)
                throw e
            }
            warnUnrecorded("DUPLICATE", sent)
            return DeadLetterRedriveResponse.from(checkNotNull(recorded(topic, partition, offset, dltTimestamp)) { "unique에 걸렸는데 이긴 기록이 없다 dltTopic=$topic dltPartition=$partition dltOffset=$offset" })
        }
        meterRegistry.counter(REDRIVEN_METRIC, "topic", originalTopic).increment()
        log.info {
            "audit action=DEAD_LETTER_REDRIVEN operator=$operator dltTopic=$topic dltPartition=$partition dltOffset=$offset " +
                "topic=$originalTopic partition=${sent.partition()} offset=${sent.offset()}"
        }
        return DeadLetterRedriveResponse.from(redrive)
    }

    /**
     * Jackson 예외 메시지는 입력 조각을 담으므로 응답과 로그에는 도메인 코드(버전 가드 등, 원인 사슬에서 찾는다)나 예외 클래스 이름만 싣는다.
     * 값이 없거나(tombstone) JSON `null`이면 consumer가 받을 이벤트가 없으므로 읽지 못한 것으로 본다.
     */
    private fun requireReadable(topic: String, value: ByteArray?) {
        val cause = try {
            if (value != null && jsonMapper.readValue(value, eventTypes.typeOf(topic)) != null) return
            "값이 없습니다"
        } catch (e: JacksonException) {
            generateSequence<Throwable>(e) { it.cause }.filterIsInstance<DomainException>().firstOrNull()?.code ?: e.javaClass.simpleName
        }
        throw DomainException(DEAD_LETTER_NOT_READABLE, "지금 코드가 읽지 못하는 메시지입니다: $cause", DomainErrorKind.UNPROCESSABLE)
    }

    private fun recorded(topic: String, partition: Int, offset: Long, timestamp: Instant) =
        redrives.findByDltTopicAndDltPartitionAndDltOffsetAndDltTimestamp(topic, partition, offset, timestamp)

    private fun send(record: ProducerRecord<Any, Any>): RecordMetadata =
        try {
            kafka.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).recordMetadata
        } catch (e: Exception) {
            // ack를 기다리다 인터럽트되면 표시를 되살려 위에서 스레드 종료 요청을 알 수 있게 하고 그대로 던진다(500).
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // ack가 상한 안에 오지 않았거나, 메타데이터 대기·전송이 Kafka 시간 초과로 끝났다. 앞은 send가 KafkaException으로 곧바로 던지고
            // 뒤는 future가 ExecutionException으로 원인을 싣는다(spring-kafka 4.1.1 KafkaTemplate.doSend). 다른 Kafka 실패(권한 등)는 500이다.
            if (e is TimeoutException || generateSequence<Throwable>(e) { it.cause }.any { it is KafkaTimeoutException }) {
                throw DependencyUnavailableException("kafka broker", e)
            }
            throw e
        }

    companion object {
        /** 처음 재투입한 메시지 수. `topic`은 원 토픽이다. 같은 레코드의 재요청은 세지 않는다. */
        const val REDRIVEN_METRIC = "event.kafka.dead-letter.redriven"
        const val INVALID_REDRIVE_REASON = "INVALID_REDRIVE_REASON"
        const val DEAD_LETTER_NOT_FOUND = "DEAD_LETTER_NOT_FOUND"
        const val DEAD_LETTER_NOT_READABLE = "DEAD_LETTER_NOT_READABLE"
        private const val SOURCE_CONSTRAINT = "ux_dead_letter_redrive_source"

        /** 컬럼 `t_dead_letter_redrive.reason varchar(500)`을 따라간다. 컬럼을 바꾸면 함께 바꾼다. */
        private const val MAX_REASON_LENGTH = 500
        private const val NUL = '\u0000'
        private val SEND_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val log by logger()
    }
}
