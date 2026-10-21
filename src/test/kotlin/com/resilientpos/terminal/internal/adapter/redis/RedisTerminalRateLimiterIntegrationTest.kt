package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.lettuce.core.cluster.SlotHash
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

/** token bucket 스크립트를 실제 Redis에서 돌린다. 단말·매장 ID는 테스트마다 새로 만들어 다른 테스트의 bucket과 섞이지 않는다. */
class RedisTerminalRateLimiterIntegrationTest(
    @Autowired private val redis: StringRedisTemplate,
) : PostgresIntegrationTest() {

    private val storeId = StoreId(UUID.randomUUID())

    private fun terminal(store: StoreId = storeId) = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), store)

    /** 기본 refill(초당 0.02개)은 테스트가 도는 몇 초 동안 토큰 1개도 채우지 않으면서, 빈 bucket이 하루 안에 차야 한다는 설정 검증은 통과한다. */
    private fun limiter(
        terminalCapacity: Long = 1_000,
        terminalRefillPerSecond: Double = 0.02,
        storeCapacity: Long = 1_000,
        storeRefillPerSecond: Double = 0.02,
    ) = RedisTerminalRateLimiter(redis, SimpleMeterRegistry(), terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond)

    @Test
    fun `단말 bucket은 capacity만큼 연달아 통과시키고 다음 요청을 거절한다`() {
        val limiter = limiter(terminalCapacity = 3)
        val terminal = terminal()

        repeat(3) { limiter.acquire(terminal) }

        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }
    }

    @Test
    fun `쉬면 refill 속도만큼 다시 차고 1초 미만 대기의 Retry-After는 1이다`() {
        // 토큰 1개는 0.5초, 키 TTL(빈 bucket이 가득 차는 시간)은 1초다. 0.6초는 둘 사이라 양쪽에 0.4초씩 여유가 있다.
        // TTL보다 오래 쉬면 키가 만료돼 가득 찬 bucket이 되므로 refill이 없어도 통과한다. capacity 1이면 두 시간이 같아져 판별할 수 없다.
        val limiter = limiter(terminalCapacity = 2, terminalRefillPerSecond = 2.0)
        val terminal = terminal()
        repeat(2) { limiter.acquire(terminal) }
        assertEquals(1, assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.retryAfterSeconds)

        Thread.sleep(600)

        limiter.acquire(terminal)
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }
    }

    @Test
    fun `capacity를 넘어 차지 않는다`() {
        val limiter = limiter(terminalCapacity = 3, terminalRefillPerSecond = 2.0)
        val terminal = terminal()
        limiter.acquire(terminal)

        // 남은 2개에 1.2초 동안 2.4개가 더 찰 시간이지만 3에서 멈춘다. 상한이 없으면 4개를 넘기려면 1초 이상 쉬어야 하고,
        // 키 TTL(빈 bucket이 가득 차는 1.5초)보다는 짧아야 한다. 더 쉬면 키가 만료돼 상한이 없어도 이 테스트가 통과한다.
        Thread.sleep(1_200)

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
        val limiter = limiter(terminalCapacity = 2, storeCapacity = 1, storeRefillPerSecond = 2.0)
        val terminal = terminal()
        limiter.acquire(terminal)
        assertContains(assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.message.orEmpty(), "scope=store")

        // 매장 bucket만 다시 찬다(0.6초면 1.2개가 찰 시간이지만 capacity 1). 단말 bucket에는 1개가 남아 있어야 한다.
        Thread.sleep(600)
        limiter.acquire(terminal)
        Thread.sleep(600)

        assertContains(assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.message.orEmpty(), "scope=terminal")
    }

    @Test
    fun `거절은 토큰을 쓰지 않고 Retry-After는 토큰 1개를 다시 얻는 시간을 초로 올림한다`() {
        // 토큰 1개는 1.5초라 Retry-After는 올림한 2다. 키 TTL(빈 bucket이 가득 차는 시간)은 3초다.
        val limiter = limiter(terminalCapacity = 2, terminalRefillPerSecond = 2.0 / 3)
        val terminal = terminal()
        repeat(2) { limiter.acquire(terminal) }

        repeat(5) {
            assertEquals(2, assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.retryAfterSeconds)
        }

        // 거절이 토큰을 썼다면 빚이 남아 1.6초 뒤에도 거절된다. TTL보다 짧게 쉰다. 길면 키가 만료돼 가득 찬 bucket이 되어 판별하지 못한다.
        Thread.sleep(1_600)
        limiter.acquire(terminal)
    }

    @Test
    fun `저장된 시각이 Redis의 지금보다 미래여도 refill이 멈추지 않는다`() {
        // failover로 새 primary의 시계가 뒤처진 상황이다. 빈 bucket의 마지막 차감 시각이 1분 뒤로 남아 있다.
        val limiter = limiter(terminalCapacity = 2, terminalRefillPerSecond = 2.0)
        val terminal = terminal()
        val key = "rate-limit:{${terminal.storeId.value}}:terminal:${terminal.terminalId.value}"
        val oneMinuteLaterMicros = (System.currentTimeMillis() + 60_000) * 1_000
        redis.opsForHash<String, String>().putAll(key, mapOf("tokens" to "0", "at" to oneMinuteLaterMicros.toString()))
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        // 토큰 1개는 0.5초다. 미래 시각이 그대로면 1분 동안 refill이 없어 거절된다.
        Thread.sleep(600)

        limiter.acquire(terminal)
    }

    @Test
    fun `두 bucket이 모두 거절하면 더 오래 기다려야 하는 쪽의 시간과 scope다`() {
        listOf(
            Triple(0.5, 0.25, "store"),
            Triple(0.25, 0.5, "terminal"),
        ).forEach { (terminalRefill, storeRefill, slower) ->
            val limiter = limiter(terminalCapacity = 1, terminalRefillPerSecond = terminalRefill, storeCapacity = 1, storeRefillPerSecond = storeRefill)
            val terminal = terminal(StoreId(UUID.randomUUID()))
            limiter.acquire(terminal)

            val rejected = assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

            // 느린 쪽은 초당 0.25개라 토큰 1개에 4초다.
            assertEquals(4, rejected.retryAfterSeconds)
            assertContains(rejected.message.orEmpty(), "scope=$slower")
        }
    }

    @Test
    fun `두 키는 같은 Cluster slot에 있고 빈 bucket이 가득 차는 시간 이하의 TTL이 걸린다`() {
        val limiter = limiter(terminalCapacity = 3, terminalRefillPerSecond = 0.5, storeCapacity = 10, storeRefillPerSecond = 2.0)
        val terminal = terminal()
        val store = terminal.storeId.value
        val terminalKey = "rate-limit:{$store}:terminal:${terminal.terminalId.value}"
        val storeKey = "rate-limit:{$store}:store"

        limiter.acquire(terminal)

        // 다른 slot이면 Cluster에서 CROSSSLOT으로 실패하고 fail-open이 보호를 조용히 끈다. standalone에서는 이 검사만 그 회귀를 잡는다.
        assertEquals(SlotHash.getSlot(terminalKey), SlotHash.getSlot(storeKey))
        listOf(terminalKey to 6_000L, storeKey to 5_000L).forEach { (key, fillMillis) ->
            val ttl = checkNotNull(redis.getExpire(key, TimeUnit.MILLISECONDS))
            assertTrue(ttl in 1..fillMillis, "$key TTL이 범위 밖이다: $ttl")
        }
    }
}
