package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.PostgresIntegrationTest
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

/** token bucket 스크립트를 실제 Redis에서 돌린다. 단말·매장 ID는 테스트마다 새로 만들어 다른 테스트의 bucket과 섞이지 않는다. */
class RedisTerminalRateLimiterIntegrationTest(
    @Autowired private val redis: StringRedisTemplate,
) : PostgresIntegrationTest() {

    private val storeId = StoreId(UUID.randomUUID())

    private fun terminal(store: StoreId = storeId) = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), store)

    private fun limiter(
        terminalCapacity: Long = 1_000,
        terminalRefillPerSecond: Double = 0.001,
        storeCapacity: Long = 1_000,
        storeRefillPerSecond: Double = 0.001,
    ) = RedisTerminalRateLimiter(redis, SimpleMeterRegistry(), terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond)

    @Test
    fun `단말 bucket은 capacity만큼 연달아 통과시키고 다음 요청을 거절한다`() {
        val limiter = limiter(terminalCapacity = 3)
        val terminal = terminal()

        repeat(3) { limiter.acquire(terminal) }

        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }
    }

    @Test
    fun `쉬면 refill 속도만큼 다시 찬다`() {
        val limiter = limiter(terminalCapacity = 3, terminalRefillPerSecond = 10.0)
        val terminal = terminal()
        repeat(3) { limiter.acquire(terminal) }
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        // 0.12초면 1.2개가 찬다.
        Thread.sleep(120)

        limiter.acquire(terminal)
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }
    }

    @Test
    fun `capacity를 넘어 차지 않는다`() {
        val limiter = limiter(terminalCapacity = 3, terminalRefillPerSecond = 10.0)
        val terminal = terminal()
        limiter.acquire(terminal)

        // 남은 2개에 0.22초 동안 2.2개가 더 찰 시간이지만 3에서 멈춘다. 키 TTL(빈 bucket이 가득 차는 0.3초)보다 짧게 쉰다.
        // 더 쉬면 키가 만료돼 가득 찬 bucket이 되므로 상한이 없어도 이 테스트가 통과한다.
        Thread.sleep(220)

        repeat(3) { limiter.acquire(terminal) }
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }
    }

    @Test
    fun `매장 bucket은 그 매장 단말 전부가 나눠 쓰고 다른 매장은 영향을 받지 않는다`() {
        val limiter = limiter(storeCapacity = 2)
        limiter.acquire(terminal())
        limiter.acquire(terminal())

        val rejected = assertFailsWith<RateLimitedException> { limiter.acquire(terminal()) }

        assertContains(rejected.message.orEmpty(), "scope=store")
        limiter.acquire(terminal(StoreId(UUID.randomUUID())))
    }

    @Test
    fun `매장 bucket이 거절하면 단말 bucket의 토큰을 차감하지 않는다`() {
        val limiter = limiter(terminalCapacity = 2, storeCapacity = 1, storeRefillPerSecond = 10.0)
        val terminal = terminal()
        limiter.acquire(terminal)
        assertContains(assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.message.orEmpty(), "scope=store")

        // 매장 bucket만 다시 찬다(0.2초면 2개가 찰 시간이지만 capacity 1). 단말 bucket에는 1개가 남아 있어야 한다.
        Thread.sleep(200)
        limiter.acquire(terminal)
        Thread.sleep(200)

        assertContains(assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.message.orEmpty(), "scope=terminal")
    }

    @Test
    fun `거절은 토큰을 쓰지 않고 Retry-After는 토큰 1개를 다시 얻는 시간을 초로 올림한다`() {
        val limiter = limiter(terminalCapacity = 1, terminalRefillPerSecond = 0.5)
        val terminal = terminal()
        limiter.acquire(terminal)

        repeat(5) {
            assertEquals(2, assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.retryAfterSeconds)
        }

        // 거절이 토큰을 썼다면 2초 뒤에도 빚이 남아 거절된다.
        Thread.sleep(2_100)
        limiter.acquire(terminal)
    }
}
