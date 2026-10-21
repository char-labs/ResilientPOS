package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.platform.logger
import com.resilientpos.terminal.AuthenticatedTerminal
import io.lettuce.core.RedisCommandExecutionException
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.NestedExceptionUtils
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * 인증된 단말 요청의 빈도 상한. 단말 bucket과 그 매장 bucket을 Lua 스크립트 한 번으로 확인하고,
 * 둘 다 토큰이 1개 이상일 때만 둘 다 하나씩 가져간다. 한쪽이 거절하면 어느 쪽도 차감하지 않고, 거절된 요청은 토큰을 쓰지 않는다.
 * 쉬는 동안 bucket이 capacity까지 차므로 오프라인에서 돌아온 단말은 capacity만큼 바로 보낼 수 있다.
 * 시각은 Redis `TIME`이라 pod 시계와 무관하다. 알고리즘은 Spring Cloud Gateway의 `request_rate_limiter.lua`를 따랐고 코드는 새로 썼다.
 *
 * 키는 `rate-limit:{매장 ID}:terminal:단말 ID`와 `rate-limit:{매장 ID}:store`다. 중괄호는 Redis Cluster hash tag다.
 * 두 키가 다른 slot이면 스크립트가 실패하고 아래 fail-open이 그 실패를 삼켜 보호가 꺼진다. 기동 검사가 그런 배포를 멈춘다.
 *
 * Redis 오류로 보는 예외는 `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)뿐이고,
 * 그때는 제한 없이 통과시킨다(fail-open). `/v1`은 Redis 장애에 막히지 않는다(application.yaml의 Redis 절).
 * 두 예외는 `redis.execute` 호출 하나만 감싸서 잡으므로 이 클래스의 프로그래밍 오류는 삼키지 않는다.
 */
@Component
class RedisTerminalRateLimiter(
    private val redis: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @Value("\${resilientpos.rate-limit.terminal.capacity}") terminalCapacity: Long,
    @Value("\${resilientpos.rate-limit.terminal.refill-per-second}") terminalRefillPerSecond: Double,
    @Value("\${resilientpos.rate-limit.store.capacity}") storeCapacity: Long,
    @Value("\${resilientpos.rate-limit.store.refill-per-second}") storeRefillPerSecond: Double,
) {

    init {
        requireValid("terminal", terminalCapacity, terminalRefillPerSecond)
        requireValid("store", storeCapacity, storeRefillPerSecond)
        // 첫 거절 전에도 시계열이 있어야 increase()가 첫 증가를 잡는다.
        SCOPES.forEach { meterRegistry.counter(METRIC, SCOPE_TAG, it) }
    }

    /** 스크립트의 ARGV. 키 순서(단말, 매장)와 같은 순서로 capacity와 초당 refill이 번갈아 온다. */
    private val bucketArgs = listOf(terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond).map { it.toString() }.toTypedArray()

    private val warnLog = Throttle()
    private val errorLog = Throttle()

    /**
     * 기동할 때 검사용 키로 스크립트를 한 번 돌린다. Redis가 기다려도 풀리지 않는 오류(권한·스크립트·slot 등)로 답하면 기동을 멈춘다.
     * 그대로 뜨면 모든 요청에서 보호가 꺼진 채 fail-open으로 통과하기 때문이다. Redis에 닿지 못하거나 일시 오류면 남기고 뜬다.
     */
    @PostConstruct
    fun checkScriptAtStartup() {
        val failure = try {
            redis.execute(ACQUIRE, PREFLIGHT_KEYS, *bucketArgs)
            return
        } catch (e: DataAccessException) {
            e
        } catch (e: IllegalStateException) {
            e
        }
        val root = NestedExceptionUtils.getMostSpecificCause(failure)
        check(!needsOperator(root)) { "Redis가 rate limit 스크립트를 거절했다. ACL·스크립트·Cluster slot을 확인한다: ${root.javaClass.simpleName}: ${root.message}" }
        log.warn { "기동 검사를 건너뛴다(Redis에 닿지 못함) cause=${root.javaClass.simpleName}: ${root.message}" }
    }

    /** 거절하면 [RateLimitedException]을 던진다. */
    fun acquire(terminal: AuthenticatedTerminal) {
        val store = terminal.storeId.value
        val keys = listOf("rate-limit:{$store}:terminal:${terminal.terminalId.value}", "rate-limit:{$store}:store")
        val result = try {
            redis.execute(ACQUIRE, keys, *bucketArgs)
        } catch (e: DataAccessException) {
            return proceedUnlimited(terminal, e)
        } catch (e: IllegalStateException) {
            return proceedUnlimited(terminal, e)
        }
        val rejected = (result[0] as Long).toInt()
        if (rejected == 0) return
        val scope = SCOPES[rejected - 1]
        meterRegistry.counter(METRIC, SCOPE_TAG, scope).increment()
        // 거절이면 기다릴 시간이 1마이크로초 이상이라 올림한 초는 1 이상이다.
        val waitMicros = result[1] as Long
        throw RateLimitedException(
            retryAfterSeconds = ceil(waitMicros / MICROS_PER_SECOND).toLong(),
            message = "scope=$scope terminalId=${terminal.terminalId.value} storeId=$store",
        )
    }

    /**
     * 기다려도 풀리지 않는 오류(사람이 설정·스크립트를 고쳐야 함)는 ERROR, 그 밖은 WARN이다. 줄에는 감싼 Spring 예외가 아니라 근본 원인을 남긴다.
     * `REJECT_COMMANDS`로 실패가 바로 나므로 장애 중 줄 수가 요청 수와 같아진다. 같은 수준은 10초에 한 줄만 남기고 그 사이 건수를 적는다.
     */
    // shortcut: Redis가 느리면 /v1 요청마다 timeout(1초)을 기다린 뒤 통과하고, 패킷이 사라지는 단절에서는 응답 없는 명령이 queue 상한(1000)까지 그렇다. 지연이 관측되면 circuit breaker로 Redis 호출 자체를 건너뛴다.
    private fun proceedUnlimited(terminal: AuthenticatedTerminal, e: RuntimeException) {
        val root = NestedExceptionUtils.getMostSpecificCause(e)
        val operator = needsOperator(root)
        val suppressed = (if (operator) errorLog else warnLog).pass(clock.millis()) ?: return
        val detail = "terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value} " +
            "cause=${root.javaClass.simpleName}: ${root.message} suppressedSinceLast=$suppressed"
        if (operator) {
            log.error { "제한 없이 통과시킨다(Redis가 명령을 거절함, 설정·스크립트 확인 필요) $detail" }
        } else {
            log.warn { "제한 없이 통과시킨다(Redis 오류) $detail" }
        }
    }

    /** Redis가 오류로 답했고 그 오류가 기다려도 풀리지 않는가. 연결 실패·timeout·끊긴 동안의 거절은 Redis의 오류 응답이 아니다. */
    private fun needsOperator(root: Throwable): Boolean =
        root is RedisCommandExecutionException && root.message.orEmpty().substringBefore(' ') !in TRANSIENT_REDIS_ERRORS

    /** 같은 수준의 로그를 [LOG_INTERVAL]에 한 줄로 줄인다. 남길 차례면 그 사이 건너뛴 수를, 아니면 null을 돌려준다. */
    private class Throttle {
        private val lastMillis = AtomicLong(Long.MIN_VALUE)
        private val skipped = AtomicLong()

        fun pass(nowMillis: Long): Long? {
            val last = lastMillis.get()
            val due = last == Long.MIN_VALUE || nowMillis - last >= LOG_INTERVAL.toMillis()
            if (!due || !lastMillis.compareAndSet(last, nowMillis)) {
                skipped.incrementAndGet()
                return null
            }
            return skipped.getAndSet(0)
        }
    }

    private companion object {
        val log by logger()
        const val MICROS_PER_SECOND = 1_000_000.0
        const val MAX_FILL_SECONDS = 86_400.0
        const val MAX_EXACT_LUA_INTEGER = 1L shl 53
        val LOG_INTERVAL: Duration = Duration.ofSeconds(10)

        /** Prometheus 이름 `inbound_rate_limited_total`. 단말·매장 ID는 label에 두지 않는다(관측성 wiki). */
        const val METRIC = "inbound.rate.limited"
        const val SCOPE_TAG = "scope"

        /** 키 순서와 같다. 스크립트가 돌려주는 거절 bucket 번호(1부터)로 찾는다. */
        val SCOPES = listOf("terminal", "store")

        /** 기동 검사용 키. 운영 키와 같은 hash tag 구조라 Cluster slot 오류도 여기서 드러난다. */
        val PREFLIGHT_KEYS = listOf("rate-limit:{preflight}:terminal:preflight", "rate-limit:{preflight}:store")

        /** 기다리면 풀리는 Redis 오류 응답 코드. 재시작 중 적재, 긴 스크립트, Cluster 재배치·장애 조치 중이다. */
        val TRANSIENT_REDIS_ERRORS = setOf("LOADING", "BUSY", "TRYAGAIN", "CLUSTERDOWN", "MASTERDOWN")

        /**
         * 0·무한대·NaN이면 스크립트가 계산에서 실패하고 fail-open이 그 실패를 삼켜 보호가 꺼진다. 하루가 넘게 걸려야 차는 bucket은
         * 단위 실수(초당을 하루당으로)다. 2^53을 넘는 capacity는 Lua double에서 1 차감이 묻혀 제한이 사라진다. 기동에서 막는다.
         */
        fun requireValid(scope: String, capacity: Long, refillPerSecond: Double) = require(
            capacity in 1..MAX_EXACT_LUA_INTEGER && refillPerSecond > 0 && refillPerSecond.isFinite() &&
                capacity / refillPerSecond <= MAX_FILL_SECONDS,
        ) {
            "resilientpos.rate-limit.$scope: capacity=$capacity refill-per-second=$refillPerSecond. " +
                "capacity는 1 이상 2^53 이하, refill은 0보다 큰 유한값이고 빈 bucket이 하루 안에 가득 차야 한다"
        }

        /**
         * bucket마다 hash에 남은 토큰(`tokens`)과 마지막 갱신 시각(`at`, 마이크로초)을 둔다. 지난 시간만큼 채우되 capacity를 넘지 않는다.
         * 모든 bucket에 토큰이 1개 이상이면 모두 하나씩 가져가고, 빈 bucket이 가득 차는 시간을 TTL로 건다(그 뒤 키가 없으면 가득 찬 것과 같다).
         * 하나라도 모자라면 쓰지 않는다(미래 시각 보정만 예외). 돌려주는 값은 거절한 bucket 번호(통과면 0)와 그 bucket이 토큰 1개를 다시 얻기까지의 마이크로초다.
         * 둘 다 모자라면 더 오래 기다려야 하는 쪽이다.
         */
        val ACQUIRE: RedisScript<List<*>> = RedisScript.of(
            """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
            local filled, ttl, rejected, wait = {}, {}, 0, 0
            for i, key in ipairs(KEYS) do
              local capacity, rate = tonumber(ARGV[i * 2 - 1]), tonumber(ARGV[i * 2]) / 1000000
              local state = redis.call('HMGET', key, 'tokens', 'at')
              local tokens = tonumber(state[1]) or capacity
              local at = tonumber(state[2]) or now
              if at > now then
                -- failover로 시계가 뒤처졌다. 거절이어도 고쳐 써야 시계가 따라잡을 때까지 refill이 멈추지 않는다.
                redis.call('HSET', key, 'at', now)
                at = now
              end
              filled[i] = math.min(capacity, tokens + math.max(0, now - at) * rate)
              ttl[i] = math.ceil(capacity / rate / 1000)
              if filled[i] < 1 then
                local needed = math.ceil((1 - filled[i]) / rate)
                if needed > wait then rejected, wait = i, needed end
              end
            end
            if rejected == 0 then
              for i, key in ipairs(KEYS) do
                redis.call('HSET', key, 'tokens', filled[i] - 1, 'at', now)
                redis.call('PEXPIRE', key, ttl[i])
              end
            end
            return {rejected, wait}
            """.trimIndent(),
            List::class.java,
        )
    }
}
