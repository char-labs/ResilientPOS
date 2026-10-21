package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.platform.logger
import com.resilientpos.terminal.AuthenticatedTerminal
import io.micrometer.core.instrument.MeterRegistry
import kotlin.math.ceil
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * 인증된 단말 요청의 빈도 상한. 단말 bucket과 그 매장 bucket을 Lua 스크립트 한 번으로 확인하고,
 * 둘 다 토큰이 1개 이상일 때만 둘 다 하나씩 가져간다. 한쪽이 거절하면 어느 쪽도 차감하지 않고, 거절된 요청은 토큰을 쓰지 않는다.
 * 쉬는 동안 bucket이 capacity까지 차므로 오프라인에서 돌아온 단말은 capacity만큼 바로 보낼 수 있다.
 * 시각은 Redis `TIME`이라 pod 시계와 무관하다. 알고리즘은 Spring Cloud Gateway의 `request_rate_limiter.lua`를 따른다.
 *
 * 키는 `rate-limit:{매장 ID}:terminal:단말 ID`와 `rate-limit:{매장 ID}:store`다. 중괄호는 Redis Cluster hash tag다.
 * 두 키가 다른 slot이면 스크립트가 실패하고 아래 fail-open이 그 실패를 삼켜 보호가 조용히 꺼진다.
 *
 * Redis 오류로 보는 예외는 `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)뿐이고,
 * 그때는 제한 없이 통과시킨다(fail-open). `/v1`은 Redis 장애에 막히지 않는다(application.yaml의 Redis 절).
 * 그 밖의 예외는 프로그래밍 오류라 삼키지 않는다.
 */
@Component
class RedisTerminalRateLimiter(
    private val redis: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${resilientpos.rate-limit.terminal.capacity}") terminalCapacity: Long,
    @Value("\${resilientpos.rate-limit.terminal.refill-per-second}") terminalRefillPerSecond: Double,
    @Value("\${resilientpos.rate-limit.store.capacity}") storeCapacity: Long,
    @Value("\${resilientpos.rate-limit.store.refill-per-second}") storeRefillPerSecond: Double,
) {

    init {
        // 0·무한대·NaN이면 스크립트가 계산에서 실패하고 fail-open이 그 실패를 삼켜 보호가 조용히 꺼진다. 기동에서 막는다.
        require(terminalCapacity >= 1 && storeCapacity >= 1) { "rate-limit capacity는 1 이상이어야 한다" }
        require(listOf(terminalRefillPerSecond, storeRefillPerSecond).all { it > 0 && it.isFinite() }) {
            "rate-limit refill-per-second는 0보다 큰 유한값이어야 한다"
        }
        // 첫 거절 전에도 시계열이 있어야 increase()가 첫 증가를 잡는다.
        SCOPES.forEach { meterRegistry.counter(METRIC, SCOPE_TAG, it) }
    }

    /** 스크립트의 ARGV. 키 순서(단말, 매장)와 같은 순서로 capacity와 초당 refill이 번갈아 온다. */
    private val bucketArgs = listOf(terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond).map { it.toString() }.toTypedArray()

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
     * 장애 중에는 /v1 요청마다 남으므로 스택 없이 한 줄이다. 스택을 붙이면 로그가 장애를 키운다.
     * 연결이 끊긴 것을 Lettuce가 아는 동안에는 명령이 바로 거절된다(`PlatformConfiguration`의 `REJECT_COMMANDS`).
     */
    // shortcut: Redis가 느리거나 패킷이 사라지는 단절(RST 없음)이면 /v1 요청마다 timeout(1초)을 기다린 뒤 통과한다. 지연이 관측되면 circuit breaker로 Redis 호출 자체를 건너뛴다.
    private fun proceedUnlimited(terminal: AuthenticatedTerminal, e: RuntimeException) =
        log.warn {
            "제한 없이 통과시킨다(Redis 오류) terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value} " +
                "cause=${e.javaClass.simpleName}: ${e.message}"
        }

    private companion object {
        val log by logger()
        const val MICROS_PER_SECOND = 1_000_000.0

        /** Prometheus 이름 `inbound_rate_limited_total`. 단말·매장 ID는 label에 두지 않는다(관측성 wiki). */
        const val METRIC = "inbound.rate.limited"
        const val SCOPE_TAG = "scope"

        /** 키 순서와 같다. 스크립트가 돌려주는 거절 bucket 번호(1부터)로 찾는다. */
        val SCOPES = listOf("terminal", "store")

        /**
         * bucket마다 hash에 남은 토큰(`tokens`)과 마지막 차감 시각(`at`, 마이크로초)을 둔다. 지난 시간만큼 채우되 capacity를 넘지 않는다.
         * 모든 bucket에 토큰이 1개 이상이면 모두 하나씩 가져가고, 빈 bucket이 가득 차는 시간을 TTL로 건다(그 뒤 키가 없으면 가득 찬 것과 같다).
         * 하나라도 모자라면 아무것도 쓰지 않는다. 돌려주는 값은 거절한 bucket 번호(통과면 0)와 그 bucket이 토큰 1개를 다시 얻기까지의 마이크로초다.
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
