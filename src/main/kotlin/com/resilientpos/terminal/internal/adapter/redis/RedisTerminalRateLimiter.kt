package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.platform.logger
import com.resilientpos.terminal.AuthenticatedTerminal
import io.lettuce.core.RedisCommandExecutionException
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.NestedExceptionUtils
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisConnectionFailureException
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
 * 두 키가 다른 slot이면 Redis Cluster에서 스크립트가 실패하고 아래 fail-open으로 보호가 꺼진다. 키는 [keys] 하나로 만들어 기동 검사도 같은 형식을 쓴다.
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
    }

    /** 스크립트의 ARGV. 키 순서(단말, 매장)와 같은 순서로 capacity와 초당 refill이 번갈아 온다. */
    private val bucketArgs = listOf(terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond).map { it.toString() }.toTypedArray()

    /** 첫 거절 전에도 시계열이 있어야 increase()가 첫 증가를 잡는다. */
    private val rejectCounters = SCOPES.associateWith { meterRegistry.counter(METRIC, SCOPE_TAG, it) }

    private val rejectLogs = SCOPES.associateWith { Throttle() }
    private val warnLog = Throttle()
    private val errorLog = Throttle()

    /** 마지막 연결 계열 실패 시각. 그 뒤 [SKIP_AFTER_CONNECTION_FAILURE] 동안은 Redis를 부르지 않는다. */
    private val connectionFailedAt = AtomicLong(NEVER)

    /**
     * 기동할 때 이 인스턴스만의 새 검사용 키로 스크립트를 한 번 돌리고 결과를 운영과 같은 함수로 읽는다. 새 bucket이라 반드시 통과 분기(HSET·PEXPIRE)까지
     * 돌아 쓰기 권한도 검사된다. 키를 pod끼리 나누면 비어 있는 bucket에서 거절 분기만 돌아 쓰기 권한 누락을 놓친다. Redis가 앱 쪽 설정 오류(권한·인증·스크립트·slot·타입)로
     * 답하면 기동을 멈춘다. 그대로 뜨면 모든 요청에서 보호가 꺼진 채 통과하기 때문이다. Redis에 닿지 못하거나 Redis 쪽 상태(OOM·READONLY·연결 수 초과)면
     * 남기고 뜬다. Redis 사고 중에도 다시 뜨는 pod와 앱 핫픽스가 막히면 안 된다.
     * `IllegalStateException`은 잡지 않는다. 기동에는 닫힌 커넥션 팩토리가 없으므로 수명 주기 문제이고, 삼키면 검사가 조용히 꺼진다.
     */
    @PostConstruct
    fun checkScriptAtStartup() {
        val failure = try {
            val (rejected, _) = verdict(redis.execute(ACQUIRE, keys("preflight:${UUID.randomUUID()}", "preflight"), *bucketArgs))
            check(rejected == 0) { "rate limit 스크립트가 새 bucket을 거절했다. 스크립트를 확인한다: rejected=$rejected" }
            return
        } catch (e: DataAccessException) {
            e
        }
        val root = NestedExceptionUtils.getMostSpecificCause(failure)
        if (isConfigError(root)) {
            throw IllegalStateException("Redis가 rate limit 스크립트를 거절했다. ACL·스크립트·Cluster slot을 확인한다: ${root.javaClass.simpleName}: ${root.message}", failure)
        }
        // 닿지 못한 채 뜨면 첫 요청들이 공유 연결 잠금에 줄을 서지 않게 창을 연 채로 시작한다.
        if (opensSkipWindow(failure, root)) connectionFailedAt.set(clock.millis())
        log.warn { "기동 검사를 건너뛴다(Redis에 닿지 못하거나 Redis 쪽 상태 오류) cause=${root.javaClass.simpleName}: ${root.message}" }
    }

    /** 거절하면 [RateLimitedException]을 던진다. */
    fun acquire(terminal: AuthenticatedTerminal) {
        val now = clock.millis()
        val failedAt = connectionFailedAt.get()
        // 창 안이면 건너뛴다. 창이 지났으면 compareAndSet에서 이긴 요청 하나만 Redis를 다시 불러 보고(탐침) 나머지는 계속 건너뛴다.
        if (failedAt != NEVER && (now - failedAt in 0 until SKIP_AFTER_CONNECTION_FAILURE.toMillis() || !connectionFailedAt.compareAndSet(failedAt, now))) {
            warnLog.skip()
            return
        }
        val (rejected, waitMicros) = try {
            verdict(redis.execute(ACQUIRE, keys(terminal.storeId.value.toString(), terminal.terminalId.value.toString()), *bucketArgs))
        } catch (e: DataAccessException) {
            return proceedUnlimited(terminal, e)
        } catch (e: IllegalStateException) {
            return proceedUnlimited(terminal, e)
        }
        // 탐침이 성공했다. 평소에는 읽기만 해서 요청마다 쓰지 않는다.
        if (connectionFailedAt.get() != NEVER) connectionFailedAt.set(NEVER)
        if (rejected == 0) return
        val scope = SCOPES[rejected - 1]
        rejectCounters.getValue(scope).increment()
        // 거절이면 기다릴 시간이 1마이크로초 이상이라 올림한 초는 1 이상이다.
        val retryAfterSeconds = ceil(waitMicros / MICROS_PER_SECOND).toLong()
        val who = "scope=$scope terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value}"
        // 요청마다 남기면 Retry-After를 무시하는 단말이 로그를 요청 수만큼 늘린다. counter는 거절마다 오른다.
        rejectLogs.getValue(scope).pass(now)?.let { log.info { "빈도 상한으로 거절했다 $who retryAfterSeconds=$retryAfterSeconds $it" } }
        throw RateLimitedException(retryAfterSeconds, who)
    }

    /**
     * 기다려도 풀리지 않는 오류(사람이 설정·스크립트를 고쳐야 함)는 ERROR, 그 밖은 WARN이다. 줄에는 감싼 Spring 예외가 아니라 근본 원인을 남긴다.
     * `REJECT_COMMANDS`로 실패가 바로 나므로 장애 중 줄 수가 요청 수와 같아진다. 같은 수준은 10초에 한 줄만 남기고 그 사이 건수와 직전 시각을 적는다.
     * 연결 계열 실패면 [SKIP_AFTER_CONNECTION_FAILURE] 동안 Redis를 부르지 않는다. 한 번도 연결되지 못한 pod는 Spring 공유 연결의 잠금 안에서
     * 요청마다 connect timeout을 기다려 사실상 멈추고, Redis가 느리거나 단절되면 요청마다 timeout이 더해지기 때문이다. 설정 오류는 건너뛰지 않는다.
     */
    // shortcut: 1초 창과 탐침 하나뿐인 차단기다. 실패율 집계가 없고 창 길이가 고정이라 Redis가 돌아온 뒤 최대 1초 보호가 없다. 창이 잦게 열리면 Resilience4j circuit breaker로 바꾼다.
    private fun proceedUnlimited(terminal: AuthenticatedTerminal, e: RuntimeException) {
        // 호출 전 시각이 아니라 실패를 본 시각이다. timeout(1초)으로 실패하면 호출 전 시각은 이미 1초 전이라 창이 바로 닫힌다.
        val now = clock.millis()
        val root = NestedExceptionUtils.getMostSpecificCause(e)
        val operator = needsOperator(root)
        if (opensSkipWindow(e, root)) connectionFailedAt.set(now)
        val logged = (if (operator) errorLog else warnLog).pass(now) ?: return
        val detail = "terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value} " +
            "cause=${root.javaClass.simpleName}: ${root.message} $logged"
        if (operator) {
            log.error { "제한 없이 통과시킨다(Redis가 명령을 거절함, 설정·스크립트 확인 필요) $detail" }
        } else {
            log.warn { "제한 없이 통과시킨다(Redis 오류) $detail" }
        }
    }

    /** Redis가 오류로 답했고 그 오류가 기다려도 풀리지 않는가. 연결 실패·timeout·끊긴 동안의 거절은 Redis의 오류 응답이 아니다. */
    private fun needsOperator(root: Throwable): Boolean =
        root is RedisCommandExecutionException && root.message.orEmpty().substringBefore(' ') !in TRANSIENT_REDIS_ERRORS

    /**
     * 연결 계열 실패면 창을 연다. 명령 단계의 오류 응답(스크립트·권한·OOM)은 Redis가 바로 답해 기다리지 않으므로 열지 않는다.
     * 연결 단계의 오류 응답(WRONGPASS·NOAUTH·연결 수 초과)은 로그는 ERROR지만 요청마다 연결을 새로 맺고 실패해 빠르지 않으므로 연다.
     */
    private fun opensSkipWindow(e: RuntimeException, root: Throwable): Boolean = !needsOperator(root) || e is RedisConnectionFailureException

    /** 앱 쪽 설정(ACL·비밀번호·스크립트·키 형식) 오류인가. OOM·READONLY·연결 수 초과 같은 Redis 쪽 상태는 아니다. 기동 검사만 쓴다. */
    private fun isConfigError(root: Throwable): Boolean {
        if (root !is RedisCommandExecutionException) return false
        val reply = root.message.orEmpty()
        return reply.substringBefore(' ') in CONFIG_REDIS_ERRORS || CONFIG_REDIS_ERROR_PREFIXES.any(reply::startsWith)
    }

    /**
     * 같은 종류의 로그를 [LOG_INTERVAL]에 한 줄로 줄인다. 남길 차례면 그 사이 건너뛴 수와 직전 줄의 시각을, 아니면 null을 돌려준다.
     * 직전 시각이 있어야 장애 사이를 넘어 이월된 건수를 가를 수 있다. 시계가 뒤로 가면 기다리지 않고 남긴다.
     */
    private class Throttle {
        private val lastMillis = AtomicLong(NEVER)
        private val skipped = AtomicLong()

        fun skip() {
            skipped.incrementAndGet()
        }

        fun pass(nowMillis: Long): Logged? {
            val last = lastMillis.get()
            // 거절 경로는 Redis를 부르기 전 시각이라 동시 요청의 완료 순서가 뒤바뀌면 조금 음수가 된다. 간격보다 크게 뒤로 간 것만 시계 역행으로 본다.
            val interval = LOG_INTERVAL.toMillis()
            val due = last == NEVER || nowMillis - last !in -interval until interval
            if (!due || !lastMillis.compareAndSet(last, nowMillis)) {
                skip()
                return null
            }
            return Logged(skipped.getAndSet(0), last.takeIf { it != NEVER })
        }
    }

    private data class Logged(val suppressed: Long, val lastMillis: Long?) {
        override fun toString() = "suppressedSinceLast=$suppressed lastLoggedAt=${lastMillis?.let(Instant::ofEpochMilli) ?: "none"}"
    }

    private companion object {
        val log by logger()
        const val MICROS_PER_SECOND = 1_000_000.0
        const val MAX_FILL_SECONDS = 86_400.0
        const val MAX_EXACT_LUA_INTEGER = 1L shl 53
        val LOG_INTERVAL: Duration = Duration.ofSeconds(10)
        val SKIP_AFTER_CONNECTION_FAILURE: Duration = Duration.ofSeconds(1)
        const val NEVER = Long.MIN_VALUE

        /** Prometheus 이름 `inbound_rate_limited_total`. 단말·매장 ID는 label에 두지 않는다(관측성 wiki). */
        const val METRIC = "inbound.rate.limited"
        const val SCOPE_TAG = "scope"

        /** 키 순서와 같다. 스크립트가 돌려주는 거절 bucket 번호(1부터)로 찾는다. */
        val SCOPES = listOf("terminal", "store")

        /** 단말 키와 매장 키. 매장 ID가 hash tag라 Redis Cluster에서 두 키가 같은 slot에 있다. 기동 검사도 이 함수를 쓴다. */
        fun keys(storeId: String, terminalId: String) = listOf("rate-limit:{$storeId}:terminal:$terminalId", "rate-limit:{$storeId}:store")

        /** 스크립트 결과(거절 bucket 번호, 기다릴 마이크로초). 기동 검사도 같은 함수로 읽어 반환 형식이 바뀌면 기동에서 드러난다. */
        fun verdict(result: List<*>): Pair<Int, Long> = (result[0] as Long).toInt() to (result[1] as Long)

        /** 기다리면 풀리는 Redis 오류 응답 코드. 재시작 중 적재, 긴 스크립트, Cluster 재배치·장애 조치 중이다. */
        val TRANSIENT_REDIS_ERRORS = setOf("LOADING", "BUSY", "TRYAGAIN", "CLUSTERDOWN", "MASTERDOWN")

        /** 기동을 멈추는 앱 쪽 설정 오류 코드. 권한, 인증, Cluster 키 배치, 키 타입이다. */
        val CONFIG_REDIS_ERRORS = setOf("NOPERM", "NOAUTH", "WRONGPASS", "CROSSSLOT", "WRONGTYPE")

        /**
         * 스크립트 오류와 막히거나 이름이 바뀐 명령. 스크립트 안에서 ACL에 막히거나 없는 명령을 부르면 NOPERM이 아니라 `ERR`로 온다(Redis 8.10 실측).
         * 연결 수 초과(`ERR max number of clients`)는 여기에 들지 않는다.
         * Redis 7 미만의 `ERR Error running script`는 스크립트 안 쓰기의 OOM·READONLY까지 감싸 와서 넣지 않는다(Redis 7 이상이 배포 전제다).
         */
        val CONFIG_REDIS_ERROR_PREFIXES = listOf(
            "ERR user_script",
            "ERR Error compiling script",
            "ERR ACL failure in script",
            "ERR Unknown Redis command called from script",
            "ERR unknown command",
        )

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
