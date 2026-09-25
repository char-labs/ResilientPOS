package com.resilientpos.platform

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 커밋 뒤 리스너가 끝내지 못한 발행 기록을 `event.publication.incomplete{status, module, listener}`로 센다. 조회만 하고 상태는 바꾸지 않는다.
 *
 * `failed`는 리스너가 던진 기록, `stale`은 완료도 실패도 아닌 채 [staleAfter]가 지난 기록(종료·프로세스 사망으로 끊긴 것)이다.
 * 재전송된 기록은 마지막 재전송 시각부터 잰다.
 * 모든 인스턴스가 같은 DB를 세므로 알림은 `max`로 모은다.
 * 조회가 실패한 인스턴스는 개수를 내리고 `event.publication.incomplete.refreshed`(마지막 성공 시각, epoch 초)를 멈춘다.
 * 낡은 개수가 `max`에 섞여 조치된 알림을 붙잡지 않게 하려는 것이다. 신선도는 인스턴스별로(또는 `min`으로) 본다.
 */
@Component
class EventPublicationMetrics(
    dataSource: DataSource,
    private val clock: Clock,
    registry: MeterRegistry,
    @Value("\${resilientpos.event-publication.stale-after:PT5M}") private val staleAfter: Duration,
) {
    // 공유 JdbcTemplate을 건드리지 않고 이 조회에만 상한을 둔다. JPA 쿼리 상한(10초)과 같은 값이다.
    // 트랜잭션 밖이라 트랜잭션 상한이 걸리지 않고, 없으면 스케줄러 스레드 하나가 소켓 상한(30초)까지 묶인다.
    private val jdbcTemplate = JdbcTemplate(dataSource).apply { queryTimeout = 10 }

    private val gauge = MultiGauge.builder("event.publication.incomplete")
        .description("완료되지 않은 이벤트 발행 기록 수")
        .register(registry)

    /** row는 이 맵을 읽는다. 값만 바뀐 조합은 다시 등록하지 않아 scrape 사이에 시계열이 빠지지 않는다. */
    @Volatile
    private var snapshot: Map<Triple<String, String, String>, Long> = emptyMap()

    private val lastRefreshed = AtomicLong(0)

    init {
        // 0이나 음수면 막 발행된 기록까지 모두 stale이 되어 알림이 쏟아진다. 설정 오류라 기동에서 막는다.
        require(staleAfter > Duration.ZERO) { "resilientpos.event-publication.stale-after는 양수여야 한다: $staleAfter" }
        Gauge.builder("event.publication.incomplete.refreshed", lastRefreshed) { it.get().toDouble() }
            .description("event.publication.incomplete를 마지막으로 갱신한 시각(epoch 초)")
            .baseUnit("seconds")
            .strongReference(true)
            .register(registry)
    }

    /**
     * 리스너 목록이 데이터에서 나오므로 주기마다 다시 등록한다. 사라진 조합의 시계열은 없어진다.
     * 조회와 등록을 한 단위로 묶는다. 두 갱신이 겹치면 먼저 조회한 쪽이 나중에 등록해 새 값을 덮을 수 있다.
     */
    @Synchronized
    @Scheduled(fixedDelayString = "PT1M")
    fun refresh() {
        val counts = try {
            // completion_date is null 조건이 미완료 인덱스를 타서 완료 행이 쌓여도 비용이 늘지 않는다.
            jdbcTemplate.query(
                """
                select status, listener_id, count(*) as n from event_publication
                where completion_date is null
                  and (status = 'FAILED' or coalesce(last_resubmission_date, publication_date) < ?)
                group by status, listener_id
                """.trimIndent(),
                { rs, _ ->
                    val status = if (rs.getString("status") == "FAILED") "failed" else "stale"
                    val listenerId = rs.getString("listener_id")
                    Triple(status, moduleName(listenerId), listenerName(listenerId)) to rs.getLong("n")
                },
                Timestamp.from(clock.instant().minus(staleAfter)),
            )
        } catch (e: DataAccessException) {
            log.warn(e) { "발행 기록을 세지 못해 개수를 내리고 이번 갱신을 건너뛴다" }
            snapshot = emptyMap()
            gauge.register(emptyList(), false)
            return
        }
        snapshot = counts.groupBy({ it.first }, { it.second }).mapValues { (_, ns) -> ns.sum() }
        // overwrite=false: 이미 있는 조합은 그대로 두고 새 조합만 등록한다. 목록에 없는 조합은 지운다.
        // 스냅샷을 먼저 바꾸므로 그 사이의 scrape에서 사라질 조합은 한 번 0으로, 새 조합은 한 번 빠질 수 있다. `> 0` 알림에는 해가 없다.
        gauge.register(
            snapshot.keys.map { key ->
                MultiGauge.Row.of(Tags.of("status", key.first, "module", key.second, "listener", key.third), this) {
                    it.snapshot[key]?.toDouble() ?: 0.0
                }
            },
            false,
        )
        lastRefreshed.set(clock.instant().epochSecond)
    }

    companion object {
        private val log by logger()

        private const val BASE_PACKAGE = "com.resilientpos."

        /**
         * `com.resilientpos.모듈....`에서 모듈. 모듈마다 같은 이름의 리스너가 생길 수 있다(`PaymentApproved`를 order와 reconciliation이 받는다).
         * 이 저장소 밖의 리스너나 id를 직접 준 리스너는 `unknown`이다.
         */
        fun moduleName(listenerId: String): String =
            listenerId.substringBefore('(').takeIf { it.startsWith(BASE_PACKAGE) }
                ?.removePrefix(BASE_PACKAGE)?.substringBefore('.') ?: "unknown"

        /** `패키지.클래스.메서드(이벤트타입)`에서 클래스 단순 이름. 중첩 클래스는 `바깥$안`으로 저장되어 안쪽 이름만 쓴다. */
        fun listenerName(listenerId: String): String =
            listenerId.substringBefore('(').substringBeforeLast('.').substringAfterLast('.').substringAfterLast('$')
    }
}
