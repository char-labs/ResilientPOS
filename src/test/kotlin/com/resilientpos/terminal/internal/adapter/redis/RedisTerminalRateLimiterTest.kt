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
import org.springframework.data.redis.RedisConnectionFailureException
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
