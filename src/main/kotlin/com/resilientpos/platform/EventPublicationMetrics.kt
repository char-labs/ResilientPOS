package com.resilientpos.platform

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 커밋 뒤 리스너가 끝내지 못한 발행 기록을 `event.publication.incomplete{status, listener}`로 센다. 조회만 하고 상태는 바꾸지 않는다.
 *
 * `failed`는 리스너가 던진 기록, `stale`은 완료도 실패도 아닌 채 [staleAfter]가 지난 기록(종료·프로세스 사망으로 끊긴 것)이다.
 * 모든 인스턴스가 같은 DB를 세므로 알림은 `max`로 모은다.
 */
@Component
class EventPublicationMetrics(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    registry: MeterRegistry,
    @Value("\${resilientpos.event-publication.stale-after:PT5M}") private val staleAfter: Duration,
) {
    private val gauge = MultiGauge.builder("event.publication.incomplete")
        .description("완료되지 않은 이벤트 발행 기록 수")
        .register(registry)

    /** 리스너 목록이 데이터에서 나오므로 주기마다 다시 등록한다. 사라진 조합의 시계열은 없어진다. */
    @Scheduled(fixedDelayString = "PT1M")
    fun refresh() {
        val counts = try {
            // completion_date is null 조건이 미완료 인덱스를 타서 완료 행이 쌓여도 비용이 늘지 않는다.
            jdbcTemplate.query(
                """
                select status, listener_id, count(*) as n from event_publication
                where completion_date is null and (status = 'FAILED' or publication_date < ?)
                group by status, listener_id
                """.trimIndent(),
                { rs, _ ->
                    val status = if (rs.getString("status") == "FAILED") "failed" else "stale"
                    (status to listenerName(rs.getString("listener_id"))) to rs.getLong("n")
                },
                Timestamp.from(clock.instant().minus(staleAfter)),
            )
        } catch (e: DataAccessException) {
            log.warn(e) { "발행 기록을 세지 못해 이번 갱신을 건너뛴다" }
            return
        }
        val rows = counts.groupBy({ it.first }, { it.second }).map { (key, ns) ->
            MultiGauge.Row.of(Tags.of("status", key.first, "listener", key.second), ns.sum())
        }
        gauge.register(rows, true)
    }

    companion object {
        private val log by logger()

        /** `패키지.클래스.메서드(이벤트타입)`에서 클래스 단순 이름. */
        fun listenerName(listenerId: String): String =
            listenerId.substringBefore('(').substringBeforeLast('.').substringAfterLast('.')
    }
}
