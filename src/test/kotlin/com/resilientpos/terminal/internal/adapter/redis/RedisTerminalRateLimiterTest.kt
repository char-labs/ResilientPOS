package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.StringRedisTemplate

/** Redis 응답을 흉내 내 스크립트 밖의 판단(장애 처리, 설정 검증, metric)을 본다. 스크립트 자체는 `RedisTerminalRateLimiterIntegrationTest`가 본다. */
@ExtendWith(OutputCaptureExtension::class)
class RedisTerminalRateLimiterTest {

    private val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID()))
    private val meterRegistry = SimpleMeterRegistry()

    private fun limiter(
        redis: StringRedisTemplate,
        terminalCapacity: Long = 10,
        terminalRefillPerSecond: Double = 1.0,
        storeCapacity: Long = 10,
        storeRefillPerSecond: Double = 1.0,
    ) = RedisTerminalRateLimiter(redis, meterRegistry, terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond)

    /** 스크립트가 [scriptResult]를 돌려주는 Redis. */
    private fun redisReturning(scriptResult: List<Long>) = mock(StringRedisTemplate::class.java) { scriptResult }

    @Test
    fun `Redis 오류면 제한 없이 통과시키고 스택 없는 WARN 한 줄을 남긴다`(output: CapturedOutput) {
        listOf(
            RedisConnectionFailureException("Unable to connect to Redis"),
            IllegalStateException("LettuceConnectionFactory was destroyed"),
            // REJECT_COMMANDS로 끊긴 동안 거절된 명령. 기다리면 풀리는 장애라 WARN이다.
            RedisSystemException("Redis exception", RedisException("Currently not connected. Commands are rejected.")),
        ).forEach { error ->
            val mark = output.all.length
            val failing = mock(StringRedisTemplate::class.java) { throw error }

            limiter(failing).acquire(terminal)

            // 장애 중에는 /v1 요청마다 남으므로 스택이 붙으면 로그가 장애를 키운다.
            val logged = output.all.substring(mark)
            assertFalse(logged.contains("\tat "), "스택 프레임이 찍혔다")
            assertFalse(logged.contains(error.javaClass.name), "예외 줄이 따로 찍혔다")
            val line = logged.lines().single { it.contains("제한 없이 통과시킨다") }
            assertContains(line, "WARN")
            assertContains(line, "제한 없이 통과시킨다(Redis 오류)")
            assertContains(line, "terminalId=${terminal.terminalId.value}")
            assertContains(line, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @Test
    fun `Redis가 명령에 오류로 답하면 통과시키되 ERROR 한 줄로 보호가 꺼졌음을 남긴다`(output: CapturedOutput) {
        val mark = output.all.length
        // 스크립트 오류·OOM·NOPERM·CROSSSLOT은 Lettuce RedisCommandExecutionException을 원인으로 담아 온다(LettuceExceptionConverter).
        val error = RedisSystemException("Error in execution", RedisCommandExecutionException("ERR user_script:1: Script attempted to access nonexistent global variable"))
        val failing = mock(StringRedisTemplate::class.java) { throw error }

        limiter(failing).acquire(terminal)

        val logged = output.all.substring(mark)
        assertFalse(logged.contains("\tat "), "스택 프레임이 찍혔다")
        val line = logged.lines().single { it.contains("제한 없이 통과시킨다") }
        assertContains(line, "ERROR")
        assertContains(line, "Redis가 명령을 거절")
        assertContains(line, "ERR user_script:1")
    }

    @Test
    fun `capacity가 1 미만이거나 refill이 0 이하·무한대·NaN이면 만들지 못한다`() {
        val redis = redisReturning(listOf(0L, 0L))
        listOf<() -> Unit>(
            { limiter(redis, terminalCapacity = 0) },
            { limiter(redis, terminalRefillPerSecond = 0.0) },
            { limiter(redis, storeCapacity = 0) },
            { limiter(redis, storeRefillPerSecond = -1.0) },
            { limiter(redis, terminalRefillPerSecond = Double.POSITIVE_INFINITY) },
            { limiter(redis, storeRefillPerSecond = Double.POSITIVE_INFINITY) },
            { limiter(redis, storeRefillPerSecond = Double.NaN) },
        ).forEach { assertFailsWith<IllegalArgumentException>(block = it) }
    }

    @Test
    fun `빈 bucket이 가득 차는 데 하루를 넘거나 capacity가 2의 53제곱을 넘으면 만들지 못하고 딱 하루는 된다`() {
        val redis = redisReturning(listOf(0L, 0L))
        listOf<() -> Unit>(
            // 초당 0.001개면 capacity 100이 차는 데 10만 초다. 초당을 하루당으로 잘못 적은 값이다.
            { limiter(redis, terminalCapacity = 100, terminalRefillPerSecond = 0.001) },
            { limiter(redis, storeCapacity = 86_401, storeRefillPerSecond = 1.0) },
            { limiter(redis, terminalCapacity = (1L shl 53) + 1, terminalRefillPerSecond = 1e12) },
        ).forEach { assertFailsWith<IllegalArgumentException>(block = it) }

        limiter(redis, terminalCapacity = 86_400, terminalRefillPerSecond = 1.0, storeCapacity = 86_400, storeRefillPerSecond = 1.0)
    }

    @Test
    fun `거절은 거절한 bucket의 scope로 counter를 올리고 두 scope는 처음부터 0으로 있다`() {
        val limiter = limiter(redisReturning(listOf(2L, 1_500_000L)))
        assertEquals(setOf("terminal", "store"), meterRegistry.find(METRIC).counters().map { it.id.getTag("scope") }.toSet())

        val rejected = assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        assertEquals(2, rejected.retryAfterSeconds)
        assertEquals(1.0, meterRegistry.counter(METRIC, "scope", "store").count())
        assertEquals(0.0, meterRegistry.counter(METRIC, "scope", "terminal").count())
    }

    private companion object {
        const val METRIC = "inbound.rate.limited"
    }
}
