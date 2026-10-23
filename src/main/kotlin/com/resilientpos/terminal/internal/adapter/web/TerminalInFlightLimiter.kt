package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.platform.logger
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.internal.adapter.LogThrottle
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * 단말별 동시 요청 상한. 한 단말은 [Kind]마다 처리 중인 요청을 하나만 가질 수 있고, 넘는 요청은 handler에 닿기 전에 거절한다.
 *
 * 센 곳은 pod 메모리다. 지키려는 Hikari 풀이 pod마다 따로라 pod마다 세야 각 풀에서 단말이 쥐는 커넥션이 묶인다(spec 결정 2).
 * 상한이 1이라 개수가 아니라 (단말, 묶음)이 맵에 있는지만 보고, 값은 자리를 잡은 시각이다. 그래서 빈 단말의 항목이 남지 않는다.
 * 거절 로그의 `heldForMs`는 누수의 신호이지 증거가 아니다(벽시계 기준이라 시계가 뒤로 가면 0으로 자른다).
 * `REQUEST`는 요청 트랜잭션이 30초라 그보다 오래 쥐면 누수를 의심한다. `SYNC`는 배치 전체에 상한이 없어 시간만으로 가르지 못하고,
 * 그 단말의 `t_sync_operation.created_at`이 멈췄는지로 본다(spec 결정 7).
 * 거절의 `Retry-After`는 [RETRY_AFTER_SECONDS]초다. 앞 요청이 언제 끝날지 알 수 없어 줄 수 있는 가장 이른 값이다.
 * 거절한 요청은 자리를 얻지 못했으므로 [release]를 부르지 않는다.
 */
@Component
class TerminalInFlightLimiter(meterRegistry: MeterRegistry, private val clock: Clock) {

    /** 상한을 따로 세는 묶음. `SYNC`는 단말 sync, `REQUEST`는 `/v1`의 나머지다. */
    enum class Kind { SYNC, REQUEST }

    private data class Slot(val terminalId: TerminalId, val kind: Kind)

    /** 값은 자리를 잡은 `clock.millis()`. */
    private val inFlight = ConcurrentHashMap<Slot, Long>()

    /** 빈도 상한(`RedisTerminalRateLimiter`)과 같은 counter이고 `scope`만 다르다. 첫 거절 전에도 시계열이 있어야 increase()가 첫 증가를 잡는다. */
    private val rejectCounter = meterRegistry.counter("inbound.rate.limited", "scope", "in_flight")

    private val rejectLogs = Kind.entries.associateWith { LogThrottle() }

    /** 자리를 얻지 못하면 [RateLimitedException]을 던진다. 얻었으면 요청이 끝날 때 [release]로 푼다. */
    fun acquire(terminal: AuthenticatedTerminal, kind: Kind) {
        val now = clock.millis()
        val heldSince = inFlight.putIfAbsent(Slot(terminal.terminalId, kind), now) ?: return
        rejectCounter.increment()
        val who = "kind=${kind.name.lowercase()} terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value}"
        // 요청마다 남기면 Retry-After를 무시하는 단말이 로그를 요청 수만큼 늘린다. counter는 거절마다 오른다.
        rejectLogs.getValue(kind).pass(now)?.let { log.info { "동시 요청 상한으로 거절했다 $who heldForMs=${(now - heldSince).coerceAtLeast(0)} $it" } }
        throw RateLimitedException(RETRY_AFTER_SECONDS, who)
    }

    fun release(terminal: AuthenticatedTerminal, kind: Kind) {
        inFlight.remove(Slot(terminal.terminalId, kind))
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 1L
        val log by logger()
    }
}
