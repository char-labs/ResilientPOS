package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import io.lettuce.core.cluster.SlotHash
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * token bucket 스크립트를 실제 Redis에서 돌린다. 단말·매장 ID는 테스트마다 새로 만들어 다른 테스트의 bucket과 섞이지 않는다.
 * 시간이 지난 상태는 sleep으로 기다리지 않고 bucket hash(`tokens`, `at`)를 Redis 시각 기준으로 직접 준비한다.
 * 기다리면 키 TTL(빈 bucket이 가득 차는 시간)이 끼어들어 결함이 있어도 가득 찬 bucket으로 통과하거나, 느린 환경에서 거짓으로 실패한다.
 */
class RedisTerminalRateLimiterIntegrationTest(
    private val redis: StringRedisTemplate,
) : PostgresIntegrationTest() {

    private val storeId = StoreId(UUID.randomUUID())

    private fun terminal(store: StoreId = storeId) = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), store)

    /** 기본 refill(초당 0.02개)은 테스트가 도는 동안 토큰 1개도 채우지 않으면서, 빈 bucket이 하루 안에 차야 한다는 설정 검증은 통과한다. */
    private fun limiter(
        terminalCapacity: Long = 1_000,
        terminalRefillPerSecond: Double = 0.02,
        storeCapacity: Long = 1_000,
        storeRefillPerSecond: Double = 0.02,
    ) = RedisTerminalRateLimiter(
        redis, SimpleMeterRegistry(), Clock.systemUTC(), terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond,
    )

    private fun terminalKey(terminal: AuthenticatedTerminal) = "rate-limit:{${terminal.storeId.value}}:terminal:${terminal.terminalId.value}"

    private fun storeKey(terminal: AuthenticatedTerminal) = "rate-limit:{${terminal.storeId.value}}:store"

    /** 스크립트와 같은 시계(Redis `TIME`)의 지금, 마이크로초. */
    private fun redisNowMicros(): Long =
        checkNotNull(redis.execute(RedisCallback { it.serverCommands().time(TimeUnit.MICROSECONDS) }))

    private fun putBucket(key: String, tokens: Double, atMicros: Long) =
        redis.opsForHash<String, String>().putAll(key, mapOf("tokens" to tokens.toString(), "at" to atMicros.toString()))

    private fun bucket(key: String): Map<String, String> = redis.opsForHash<String, String>().entries(key)

    @Test
    fun `단말 bucket은 capacity만큼 연달아 통과시키고 다음 요청을 거절한다`() {
        val limiter = limiter(terminalCapacity = 3)
        val terminal = terminal()

        repeat(3) { limiter.acquire(terminal) }

        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }
    }

    @Test
    fun `지난 시간만큼 refill 속도로 차고 1초 미만 대기의 Retry-After는 1이다`() {
        val limiter = limiter(terminalCapacity = 2, terminalRefillPerSecond = 2.0)
        val terminal = terminal()
        // 0.6초 전에 비었다. 그동안 1.2개가 찼다.
        putBucket(terminalKey(terminal), tokens = 0.0, atMicros = redisNowMicros() - 600_000)

        limiter.acquire(terminal)

        // 남은 0.2개가 1개가 되려면 0.4초다.
        assertEquals(1, assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.retryAfterSeconds)
    }

    @Test
    fun `capacity를 넘어 차지 않는다`() {
        val limiter = limiter(terminalCapacity = 3, terminalRefillPerSecond = 2.0)
        val terminal = terminal()
        // 1.2초 전에 2개였다. 상한이 없으면 4.4개다.
        putBucket(terminalKey(terminal), tokens = 2.0, atMicros = redisNowMicros() - 1_200_000)

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
    fun `매장 bucket이 거절하면 단말 bucket에 아무것도 쓰지 않는다`() {
        val limiter = limiter(terminalCapacity = 2, storeCapacity = 1)
        val terminal = terminal()
        putBucket(storeKey(terminal), tokens = 0.0, atMicros = redisNowMicros())

        assertContains(assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.message.orEmpty(), "scope=store")

        // 단말 bucket이 차감됐다면 hash가 생겼다. 없으면 가득 찬 bucket과 같다.
        assertFalse(redis.hasKey(terminalKey(terminal)), "매장이 거절했는데 단말 bucket을 썼다")
    }

    @Test
    fun `거절은 bucket을 바꾸지 않고 Retry-After는 토큰 1개를 다시 얻는 시간을 초로 올림한다`() {
        // 토큰 1개는 1.5초라 Retry-After는 올림한 2다.
        val limiter = limiter(terminalCapacity = 2, terminalRefillPerSecond = 2.0 / 3)
        val terminal = terminal()
        repeat(2) { limiter.acquire(terminal) }
        val before = bucket(terminalKey(terminal))

        repeat(5) {
            assertEquals(2, assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }.retryAfterSeconds)
        }

        assertEquals(before, bucket(terminalKey(terminal)))
    }

    @Test
    fun `저장된 시각이 Redis의 지금보다 미래면 거절이어도 지금으로 고쳐 써서 refill이 멈추지 않는다`() {
        // failover로 새 primary의 시계가 뒤처진 상황이다. 빈 bucket의 마지막 갱신 시각이 1분 뒤로 남아 있다.
        val limiter = limiter(terminalCapacity = 2, terminalRefillPerSecond = 2.0)
        val terminal = terminal()
        putBucket(terminalKey(terminal), tokens = 0.0, atMicros = redisNowMicros() + 60_000_000)

        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        // 미래 시각이 그대로면 1분 동안 refill이 없다.
        val at = checkNotNull(bucket(terminalKey(terminal))["at"]).toLong()
        assertTrue(at <= redisNowMicros(), "미래 시각이 남았다: $at")
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
    fun `두 키는 같은 Cluster slot에 있고 빈 bucket이 가득 차는 시간만큼 TTL이 걸린다`() {
        val limiter = limiter(terminalCapacity = 3, terminalRefillPerSecond = 0.5, storeCapacity = 10, storeRefillPerSecond = 2.0)
        val terminal = terminal()

        limiter.acquire(terminal)

        // 다른 slot이면 Cluster에서 CROSSSLOT으로 실패한다. standalone에서는 이 검사만 그 회귀를 잡는다.
        assertEquals(SlotHash.getSlot(terminalKey(terminal)), SlotHash.getSlot(storeKey(terminal)))
        // 방금 쓴 키의 TTL은 가득 차는 시간 전체다. 짧으면 bucket이 일찍 가득 찬 상태로 돌아가 설정보다 큰 burst를 허용한다.
        listOf(terminalKey(terminal) to 6_000L, storeKey(terminal) to 5_000L).forEach { (key, fillMillis) ->
            val ttl = checkNotNull(redis.getExpire(key, TimeUnit.MILLISECONDS))
            assertTrue(ttl in (fillMillis - 1_000)..fillMillis, "$key TTL이 범위 밖이다: $ttl")
        }
    }

    @Test
    fun `ACL이 스크립트 안의 쓰기 명령을 막으면 기동 검사가 기동을 멈춘다`() {
        // 스크립트 안의 ACL 거부 문구는 Redis 버전마다 다르다. 단위 테스트의 문구 목록이 이 이미지의 실제 응답과 맞는지 본다.
        val container = PostgresIntegrationTest.redis
        val user = "rate-limit-no-hset-${UUID.randomUUID()}"
        assertEquals("OK", container.execInContainer("redis-cli", "ACL", "SETUSER", user, "on", ">pw", "~*", "+@all", "-hset").stdout.trim())
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(container.host, container.getMappedPort(6379)).apply {
                username = user
                setPassword("pw")
            },
        ).apply {
            afterPropertiesSet()
            start()
        }
        try {
            val restricted = StringRedisTemplate(factory)
            // 인증은 된다. 아래 실패가 WRONGPASS가 아니라 스크립트 안의 HSET 거부라는 뜻이다.
            assertFalse(restricted.hasKey("rate-limit:{acl}:probe"))
            val limiter = RedisTerminalRateLimiter(restricted, SimpleMeterRegistry(), Clock.systemUTC(), 1_000, 0.02, 1_000, 0.02)

            assertFailsWith<IllegalStateException> { limiter.checkScriptAtStartup() }
        } finally {
            factory.destroy()
            container.execInContainer("redis-cli", "ACL", "DELUSER", user)
        }
    }
}
