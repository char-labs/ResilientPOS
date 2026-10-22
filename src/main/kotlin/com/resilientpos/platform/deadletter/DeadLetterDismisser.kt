package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.platform.Tx
import com.resilientpos.platform.isUniqueViolationOf
import com.resilientpos.platform.logger
import jakarta.persistence.EntityManager
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * 플랫폼 운영자의 DLT 폐기. 재투입하지 않기로 한 레코드에 "넣지 않음"을 사유와 함께 기록한다. 순서는 사유 검사 → DLT 읽기 → 기록 확인 → 기록이다.
 * 1. 사유는 재투입과 같은 규칙으로 검사한다([trimmedReasonOrThrow]). 어긋나면 `INVALID_DISMISS_REASON`이고 DLT를 읽지 않는다.
 * 2. DLT에서 레코드를 읽는다. 위치 검증과 시간 상한은 목록·재투입과 같고([DeadLetterReader]), 그 offset에 레코드가 없으면 [DeadLetterRedriver.DEAD_LETTER_NOT_FOUND]다.
 *    레코드 값은 읽지 않는다. 지금 코드가 읽지 못하는 메시지도 폐기된다.
 * 3. 그 레코드(좌표와 레코드 시각)의 기록이 있으면 처음 기록을 돌려준다. 사유가 달라도 바꾸지 않는다. 재요청도 브로커를 읽으므로 브로커 장애면 기록이 있어도 503이다.
 * 4. `Tx.masterTx`에서 기록을 넣는다. 같은 레코드를 동시에 요청하면 unique가 기록을 하나로 묶고 진 쪽은 이긴 쪽 기록을 돌려준다.
 *
 * 브로커에는 아무것도 보내지 않는다. 재투입과 서로 확인하지 않아 재투입한 레코드도 폐기되고 폐기한 레코드도 재투입된다.
 * 감사 INFO 한 줄은 처음 기록했을 때만 커밋 뒤에 남긴다. 사유 원문은 자유 입력이라 싣지 않는다(DB 기록이 정본이다). counter는 두지 않는다.
 */
@Component
class DeadLetterDismisser(
    private val reader: DeadLetterReader,
    private val dismissals: DeadLetterDismissalJpaRepository,
    private val entityManager: EntityManager,
    private val identifierGenerator: IdentifierGenerator,
) {

    /** [operator]는 운영자 토큰의 `sub`다. 같은 레코드의 기록이 커밋에서 겹치면 여기서 가려야 하므로 바깥 트랜잭션 안에서 부르지 않는다. */
    fun dismiss(topic: String, partition: Int, offset: Long, reason: String, operator: String): DeadLetterDismissResponse {
        val trimmedReason = trimmedReasonOrThrow(reason, INVALID_DISMISS_REASON)
        val record = reader.readRecord(topic, partition, offset)
            ?: throw DomainException(DeadLetterRedriver.DEAD_LETTER_NOT_FOUND, "그 offset에 DLT 레코드가 없습니다.", DomainErrorKind.NOT_FOUND)
        val dltTimestamp = Instant.ofEpochMilli(record.timestamp())
        recorded(topic, partition, offset, dltTimestamp)?.let { return DeadLetterDismissResponse.from(it) }
        val dismissal = DeadLetterDismissalEntity.of(identifierGenerator.next(), topic, partition, offset, dltTimestamp, operator, trimmedReason)
        try {
            Tx.masterTx { entityManager.persist(dismissal) }
        } catch (e: DataIntegrityViolationException) {
            if (!e.isUniqueViolationOf(SOURCE_CONSTRAINT)) throw e
            return DeadLetterDismissResponse.from(checkNotNull(recorded(topic, partition, offset, dltTimestamp)) { "unique에 걸렸는데 이긴 기록이 없다 dltTopic=$topic dltPartition=$partition dltOffset=$offset" })
        }
        log.info { "audit action=DEAD_LETTER_DISMISSED operator=$operator dltTopic=$topic dltPartition=$partition dltOffset=$offset" }
        return DeadLetterDismissResponse.from(dismissal)
    }

    private fun recorded(topic: String, partition: Int, offset: Long, timestamp: Instant) =
        dismissals.findByDltTopicAndDltPartitionAndDltOffsetAndDltTimestamp(topic, partition, offset, timestamp)

    companion object {
        const val INVALID_DISMISS_REASON = "INVALID_DISMISS_REASON"
        private const val SOURCE_CONSTRAINT = "ux_dead_letter_dismissal_source"
        private val log by logger()
    }
}
