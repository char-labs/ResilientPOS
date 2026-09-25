package com.resilientpos.platform

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 커밋 뒤 리스너가 끝내지 못한 발행 기록을 `event.publication.incomplete{status, module, listener}`로 센다. 조회만 하고 상태는 바꾸지 않는다.
 *
 * `failed`는 리스너가 던진 기록(`status`가 비어 있는 미완료 행도 Modulith처럼 실패로 본다), `stale`은 `PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채
 * [staleAfter]가 지난 기록(종료·프로세스 사망으로 끊긴 것)이다.
 * 재전송된 기록은 마지막 재전송 시각부터 잰다.
 * 모든 인스턴스가 같은 DB를 세므로 알림은 `max`로 모은다.
 * 조회가 실패한 인스턴스는 개수를 내리고 `event.publication.incomplete.refreshed`(마지막 성공 시각, epoch 초)를 멈춘다.
 * 낡은 개수가 `max`에 섞여 조치된 알림을 붙잡지 않게 하려는 것이다. 신선도는 인스턴스별로(또는 `min`으로) 본다.
 *
 * 갱신은 Spring의 공용 스케줄러가 아니라 전용 스레드 하나에서 돈다. 공용 스케줄러는 스레드가 하나라 느린 조회가
 * Modulith Moments의 시간 이벤트 발행까지 붙잡는다. `@EnableScheduling`이나 Moments 설정과도 무관하게 돈다.
 * 시작과 종료는 [SmartLifecycle]이 맡는다. 모든 싱글턴(Flyway 마이그레이션 포함)이 준비된 뒤 시작하고,
 * 빈을 파괴하기 전에 멈춰 닫히는 `DataSource`에 조회가 닿지 않게 한다.
 */
@Component
class EventPublicationMetrics(
    dataSource: DataSource,
    private val clock: Clock,
    registry: MeterRegistry,
    @Value("\${resilientpos.event-publication.stale-after:PT5M}") private val staleAfter: Duration,
) : SmartLifecycle {
    // 공유 JdbcTemplate을 건드리지 않고 이 조회에만 상한을 둔다. JPA 쿼리 상한(10초)과 같은 값이다.
    // 트랜잭션 밖이라 트랜잭션 상한이 걸리지 않고, 없으면 스케줄러 스레드 하나가 소켓 상한(30초)까지 묶인다.
    // 커넥션 대기는 Hikari connection-timeout(3초)이 따로 막는다.
    private val jdbcTemplate = JdbcTemplate(dataSource).apply { queryTimeout = 10 }

    private var scheduler: ScheduledExecutorService? = null

    /** 멈춘 뒤 모니터를 기다리던 갱신이 들어와도 조회하지 않게 한다. 모니터 진입은 인터럽트로 깨지 않는다. */
    @Volatile
    private var stopped = false

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

    override fun start() {
        stopped = false
        scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, THREAD_NAME).apply { isDaemon = true } }.apply {
            // refresh()가 RuntimeException을 삼키지만 Error가 새면 ScheduledExecutorService는 이후 실행을 조용히 멈춘다.
            scheduleWithFixedDelay(
                { runCatching { refresh() }.onFailure { log.error(it) { "발행 기록 metric 갱신이 실패했다" } } },
                0, INTERVAL.toSeconds(), TimeUnit.SECONDS,
            )
        }
    }

    override fun stop() {
        stopped = true
        scheduler?.run {
            shutdownNow()
            // 조회 중이면 쿼리 상한(10초)과 커넥션 대기(3초) 안에 끝난다.
            if (!awaitTermination(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) log.warn { "발행 기록 metric 갱신이 제때 멈추지 않았다" }
        }
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    @Synchronized
    fun refresh() {
        if (stopped) return
        val counts = try {
            // completion_date is null 조건이 미완료 인덱스를 타서 완료 행이 쌓여도 비용이 늘지 않는다.
            jdbcTemplate.query(
                """
                select status, listener_id, count(*) as n from event_publication
                where completion_date is null
                  and (
                    status = 'FAILED' or status is null
                    or (status in ('PUBLISHED', 'PROCESSING', 'RESUBMITTED')
                        and coalesce(last_resubmission_date, publication_date) < ?)
                  )
                group by status, listener_id
                """.trimIndent(),
                { rs, _ ->
                    // Modulith의 실패 조회(FailedEventPublications)도 status가 비어 있는 미완료 행을 실패로 본다.
                    val raw = rs.getString("status")
                    val status = if (raw == null || raw == "FAILED") "failed" else "stale"
                    val listenerId = rs.getString("listener_id")
                    Triple(status, moduleName(listenerId), listenerName(listenerId)) to rs.getLong("n")
                },
                Timestamp.from(clock.instant().minus(staleAfter)),
            )
        } catch (e: RuntimeException) {
            // DataAccessException만 잡으면 행 매핑에서 난 예외가 새어 낡은 개수가 남는다.
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

        const val THREAD_NAME = "event-publication-metrics"
        val INTERVAL: Duration = Duration.ofMinutes(1)
        private val STOP_TIMEOUT: Duration = Duration.ofSeconds(15)

        private const val BASE_PACKAGE = "com.resilientpos."

        /**
         * `com.resilientpos.모듈....`에서 모듈. 모듈마다 같은 이름의 리스너가 생길 수 있다(`PaymentApproved`를 order와 reconciliation이 받는다).
         * 이 저장소 밖의 리스너, 기본 패키지 바로 아래 클래스, id를 직접 준 리스너는 `unknown`이다.
         */
        fun moduleName(listenerId: String): String {
            // 메서드와 클래스를 떼고 남은 패키지에서 찾는다. 기본 패키지 바로 아래 클래스는 모듈이 없다.
            val pkg = listenerId.substringBefore('(').substringBeforeLast('.').substringBeforeLast('.')
            return if (pkg.startsWith(BASE_PACKAGE)) pkg.removePrefix(BASE_PACKAGE).substringBefore('.') else "unknown"
        }

        /** `패키지.클래스.메서드(이벤트타입)`에서 클래스 단순 이름. 중첩 클래스는 `바깥$안`으로 저장되어 안쪽 이름만 쓴다. */
        fun listenerName(listenerId: String): String =
            listenerId.substringBefore('(').substringBeforeLast('.').substringAfterLast('.').substringAfterLast('$')
    }
}
