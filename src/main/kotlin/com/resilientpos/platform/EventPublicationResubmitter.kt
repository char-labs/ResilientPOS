package com.resilientpos.platform

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
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
 * 1. 끊긴 기록(`PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채 마지막 시도부터 [staleAfter]가 지남)을 `FAILED`로 바꾼다.
 *    조건을 건 UPDATE 한 문장이다. Modulith staleness 모니터는 규모 때문에 쓰지 않는다. 모니터도 pod마다 돌지만 상태마다
 *    그 상태의 행을 직렬화된 이벤트까지 모두 읽어 메모리에서 시각으로 거르고 한 건씩 UPDATE한다. 브로커 장애 중에는 판정
 *    기간 동안 들어온 전송 대기 행이 모두 읽힌다.
 * 2. `FAILED` 기록을 다시 보낸다. [Lock]으로 pod 사이를 줄 세우되 트랜잭션은 열지 않는다([Lock.executeWithLockNoTx]).
 *    Modulith의 재전송 표시(완료되지 않았고 `status != RESUBMITTED`인 행만 바꾸는 조건부 UPDATE)가 한 건씩 바로 커밋되므로
 *    다음 pod는 표시된 행을 건너뛰고, 비동기 externalizer가 완료 표시를 하려고 행 잠금을 기다리지 않는다. 완료된 행은 표시되지
 *    않는다. 다만 표시 직후 Modulith가 리스너 호출 전에 그 행을 `PROCESSING`으로 바꾸므로, 락이 빠지면(fail-open, TTL 초과)
 *    같은 `FAILED` 행을 먼저 읽어 둔 pod가 처리 중인 행을 다시 표시해 보낼 수 있다. 락은 이 중복 전송을 줄인다.
 *    이 중복과, 전송 뒤 완료 표시 전에 프로세스가 죽거나 전송이 [staleAfter]를 넘겨 끊긴 기록이 된 행의 재전송은 멱등
 *    consumer가 견딘다.
 * 3. 완료 기록을 지운다. 멱등 DELETE라 락 밖에서 따로 커밋한다. 실패해도 재전송에 번지지 않는다.
 * 앞 단계가 던지면 뒤 단계는 그 주기에 건너뛰고 다음 주기에 다시 한다.
 *
 * 재전송 횟수에 상한을 두지 않는다. Modulith는 `FAILED` 행을 오래된 것부터 읽은 뒤에 거르므로 상한에 걸린 행이 읽기 자리를 차지한다.
 * 영영 나가지 못하는 행은 두 가지다. 리스너가 사라진 행은 Modulith가 로그를 남기고 `FAILED`로 돌린 뒤 다음 행으로 넘어가며,
 * [BATCH_SIZE]를 넘게 쌓이면 새 실패도 막힌다. 역직렬화할 수 없는 행은 그 재전송 실행 전체를 끊는다(spring-modulith#1904).
 * 그 행은 `RESUBMITTED`로 남아 다음 주기에는 건너뛰어지고, 1단계가 `FAILED`로 되돌릴 때마다 한 번씩 실행을 끊는다.
 * 그 실행에서 뒤에 올 행과 3단계는 다음 주기로 밀린다. 찾는 SQL과 조치는 도메인 모델 §4.3에 있다.
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
    meterRegistry: MeterRegistry,
) {
    // 공유 JdbcTemplate을 건드리지 않고 이 문장에만 상한을 둔다. 트랜잭션 밖이라 트랜잭션 상한이 걸리지 않아,
    // 없으면 잠금에 걸린 UPDATE가 소켓 상한(30초)까지 공용 스케줄러 스레드를 붙잡는다. JPA 쿼리 상한(10초)과 같은 값이다.
    private val jdbcTemplate = JdbcTemplate(dataSource).apply { queryTimeout = 10 }

    // Modulith가 읽은 행마다 술어를 부른 뒤 재전송 표시를 하므로 보낸 수가 아니라 고른 수다. 락이 빠져 두 pod가 같은 행을 읽으면 두 번 센다.
    private val selected = Counter.builder("event.publication.resubmit.selected")
        .description("재전송 주기가 다시 보내려고 읽은 FAILED 발행 기록 수")
        .register(meterRegistry)

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
            failed.resubmit(ResubmissionOptions.defaults().withBatchSize(BATCH_SIZE).withMaxInFlight(BATCH_SIZE).withFilter { selected.increment(); true })
        }
        completed.deletePublicationsOlderThan(retention)
    }

    /** 끊긴 기록의 기준은 metric의 `stale`과 같다(`EventPublicationMetrics`). 여러 pod가 겹쳐 돌아도 같은 행을 같은 값으로 바꾼다. */
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
    }

    companion object {
        const val LOCK_KEY = "platform:event-publication:resubmit"

        /** 한 주기에 다시 보내는 최대 건수이자 동시에 재전송 중일 수 있는 최대 건수다. */
        private const val BATCH_SIZE = 500
    }
}
