package com.resilientpos.platform

import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import javax.sql.DataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 브로커로 보내지 못한 발행 기록을 주기적으로 다시 보내고, 완료 뒤 [retention]이 지난 기록을 지운다.
 *
 * 한 주기는 세 단계다.
 * 1. 끊긴 기록(`PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채 마지막 시도부터 [staleAfter]가 지남)을 `FAILED`로 바꾸고,
 *    완료됐는데 `RESUBMITTED`로 남은 행을 `COMPLETED`로 돌린다. 둘 다 조건을 건 UPDATE 한 문장씩이다.
 *    Modulith staleness 모니터를 쓰지 않는다. 모니터는 `RESUBMITTED`도 최초 발행 시각으로 재서 방금 다시 보낸 행을
 *    곧바로 되돌리고, 상태로 고른 뒤 ID로 바꿔 그 사이에 완료된 행을 `FAILED`로 덮는다.
 *    완료됐는데 `RESUBMITTED`인 행은 락이 fail-open일 때 생긴다. 한 pod가 완료시킨 행을 다른 pod가 다시 표시하고,
 *    Modulith의 완료 표시는 `completion_date`가 빈 행만 바꾸기 때문이다. 그대로 두면 정리될 때까지 `maxInFlight` 한 칸을 차지한다.
 * 2. `FAILED` 기록을 다시 보낸다. [Lock]으로 pod 사이를 줄 세우되 트랜잭션은 열지 않는다([Lock.executeWithLockNoTx]).
 *    Modulith의 재전송 표시(`status != RESUBMITTED`인 행만 바꾸는 조건부 UPDATE)가 한 건씩 바로 커밋되므로 다음 pod는 표시된
 *    행을 건너뛰고, 비동기 externalizer가 완료 표시를 하려고 행 잠금을 기다리지 않는다. 락이 fail-open으로 빠질 때 남는
 *    중복 전송은 멱등 consumer가 견딘다.
 * 3. 완료 기록을 지운다. 멱등 DELETE라 락 밖에서 따로 커밋한다. 실패해도 재전송에 번지지 않는다.
 * 앞 단계가 던지면 뒤 단계는 그 주기에 건너뛰고 다음 주기에 다시 한다.
 *
 * 재전송 횟수에 상한을 두지 않는다. Modulith는 `FAILED` 행을 오래된 것부터 읽은 뒤에 거르므로 상한에 걸린 행이 읽기 자리를 차지한다.
 * 영영 나가지 못하는 행(리스너가 사라짐, 역직렬화 실패)이 [BATCH_SIZE]를 넘게 쌓이면 새 실패도 막힌다. 찾는 SQL과 조치는
 * 도메인 모델 §4.3에 있다.
 * 작업이 던지면 Spring 스케줄러가 로그를 남기고 다음 주기를 그대로 돈다.
 */
@Component
class EventPublicationResubmitter(
    private val failed: FailedEventPublications,
    private val completed: CompletedEventPublications,
    dataSource: DataSource,
    private val clock: Clock,
    @Value("\${resilientpos.event-publication.stale-after}") private val staleAfter: Duration,
    @Value("\${resilientpos.event-publication.retention}") private val retention: Duration,
) {
    // 공유 JdbcTemplate을 건드리지 않고 이 문장에만 상한을 둔다. 트랜잭션 밖이라 트랜잭션 상한이 걸리지 않아,
    // 없으면 잠금에 걸린 UPDATE가 소켓 상한(30초)까지 공용 스케줄러 스레드를 붙잡는다. JPA 쿼리 상한(10초)과 같은 값이다.
    private val jdbcTemplate = JdbcTemplate(dataSource).apply { queryTimeout = 10 }

    init {
        // 0이면 완료 행이 곧바로 지워진다. 설정 오류라 기동에서 막는다. stale-after는 EventPublicationMetrics가 검사한다.
        require(retention > Duration.ZERO) { "resilientpos.event-publication.retention은 양수여야 한다: $retention" }
    }

    @Scheduled(
        fixedDelayString = "\${resilientpos.event-publication.resubmit-interval}",
        initialDelayString = "\${resilientpos.event-publication.resubmit-interval}",
    )
    fun resubmitAndPurge() {
        markStalePublicationsFailed()
        Lock.executeWithLockNoTx(LOCK_KEY) {
            failed.resubmit(ResubmissionOptions.defaults().withBatchSize(BATCH_SIZE).withMaxInFlight(BATCH_SIZE))
        }
        completed.deletePublicationsOlderThan(retention)
    }

    /**
     * 끊긴 기록의 기준은 metric의 `stale`과 같다(`EventPublicationMetrics`). 여러 pod가 겹쳐 돌아도 같은 행을 같은 값으로 바꾼다.
     * 끊긴 기록을 먼저 고른다. `completion_date is null` 조건이 완료된 재전송 행을 끊긴 기록에서 뺀다.
     */
    internal fun markStalePublicationsFailed() {
        jdbcTemplate.update(
            """
            update event_publication set status = 'FAILED'
            where completion_date is null
              and status in ('PUBLISHED', 'PROCESSING', 'RESUBMITTED')
              and coalesce(last_resubmission_date, publication_date) < ?
            """.trimIndent(),
            Timestamp.from(clock.instant().minus(staleAfter)),
        )
        jdbcTemplate.update("update event_publication set status = 'COMPLETED' where status = 'RESUBMITTED' and completion_date is not null")
    }

    companion object {
        const val LOCK_KEY = "platform:event-publication:resubmit"

        /** 한 주기에 다시 보내는 최대 건수이자 동시에 재전송 중일 수 있는 최대 건수다. */
        private const val BATCH_SIZE = 500
    }
}
