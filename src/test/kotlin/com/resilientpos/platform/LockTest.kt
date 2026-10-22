package com.resilientpos.platform

import com.resilientpos.foundation.UuidV7IdentifierGenerator
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.atMost
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

/**
 * 잠금 저장소가 응답하지 않아도 일을 멈추지 않는지 본다. 방어선은 DB 제약이라서 잠금은 fail-open이다.
 */
class LockTest {

    private val registry = SimpleMeterRegistry()

    private fun lock(redis: StringRedisTemplate) = RedisDistributedLock(redis, UuidV7IdentifierGenerator(), registry)

    private fun failOpenCount(lock: String, reason: String): Double =
        registry.find("lock.fail.open").tags("lock", lock, "reason", reason).counter()?.count() ?: 0.0

    private fun waitCount(lock: String, outcome: String): Long =
        registry.find("distributed.lock.wait").tags("lock", lock, "outcome", outcome).timer()?.count() ?: 0

    private fun heldCount(lock: String): Long = registry.find("distributed.lock.held").tags("lock", lock).timer()?.count() ?: 0

    private fun redisAnswering(setIfAbsent: Boolean): StringRedisTemplate {
        @Suppress("UNCHECKED_CAST") // mock(ValueOperations::class.java)는 타입 인자를 모른다. 이 테스트는 String 키·값만 쓴다.
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).willReturn(setIfAbsent)
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willReturn(values)
        return redis
    }

    @Test
    fun `잠그면 대기 시간과 보유 시간을 키 앞 두 마디로 하나씩 남긴다`() {
        val lock = lock(redisAnswering(setIfAbsent = true))

        val token = assertNotNull(lock.tryAcquire("order:create-lock:terminal:client-order"))
        lock.release("order:create-lock:terminal:client-order", token)

        assertEquals(1, waitCount("order:create-lock", "acquired"))
        assertEquals(1, heldCount("order:create-lock"))
    }

    @Test
    fun `한 번이라도 다시 시도해서 얻으면 waited로 남긴다`() {
        @Suppress("UNCHECKED_CAST") // mock(ValueOperations::class.java)는 타입 인자를 모른다. 이 테스트는 String 키·값만 쓴다.
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).willReturn(false, true)
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willReturn(values)

        assertNotNull(lock(redis).tryAcquire("order:create-lock:terminal:client-order"))

        assertEquals(1, waitCount("order:create-lock", "waited"))
        assertEquals(0, waitCount("order:create-lock", "acquired"))
    }

    @Test
    fun `잠그지 못하면 그 결과의 대기 시간만 남기고 보유 시간은 남기지 않는다`() {
        assertNull(lock(redisAnswering(setIfAbsent = false)).tryAcquire("payment:checkout-lock:order:1"))
        val down = mock(StringRedisTemplate::class.java)
        given(down.opsForValue()).willThrow(RedisConnectionFailureException("Redis down"))
        assertNull(lock(down).tryAcquire("payment:checkout-lock:order:2"))

        assertEquals(1, waitCount("payment:checkout-lock", "timeout"))
        assertEquals(1, waitCount("payment:checkout-lock", "error"))
        assertEquals(0, heldCount("payment:checkout-lock"))
    }

    @Test
    fun `Redis 오류면 던지지 않고 토큰 없이 돌아오며 키 앞 두 마디와 error로 센다`() {
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willThrow(RedisConnectionFailureException("Redis down"))

        assertNull(lock(redis).tryAcquire("order:create-lock:terminal:client-order"))
        assertEquals(1.0, failOpenCount("order:create-lock", "error"))
    }

    @Test
    fun `대기 한도를 넘겨 잠금 없이 진행하면 timeout으로 센다`() {
        @Suppress("UNCHECKED_CAST") // mock(ValueOperations::class.java)는 타입 인자를 모른다. 이 테스트는 String 키·값만 쓴다.
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).willReturn(false)
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willReturn(values)

        assertNull(lock(redis).tryAcquire("platform:event-publication:resubmit"))
        assertEquals(1.0, failOpenCount("platform:event-publication", "timeout"))
    }

    @Test
    fun `오래 기다리면 재시도 간격이 늘어 대기 한도까지 SET을 마흔 번 넘게 보내지 않는다`() {
        @Suppress("UNCHECKED_CAST") // mock(ValueOperations::class.java)는 타입 인자를 모른다. 이 테스트는 String 키·값만 쓴다.
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).willReturn(false)
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willReturn(values)

        assertNull(lock(redis).tryAcquire("order:create-lock:terminal:client-order"))

        // 10ms 고정 간격이면 대기 한도(1.5초)까지 약 150번이다. 100ms까지 늘면 약 20번이다.
        verify(values, atMost(40)).setIfAbsent(anyString(), anyString(), any(Duration::class.java))
    }

    @Test
    fun `해제 알림을 받으면 재시도 간격을 기다리지 않고 바로 다시 시도한다`() {
        @Suppress("UNCHECKED_CAST") // mock(ValueOperations::class.java)는 타입 인자를 모른다. 이 테스트는 String 키·값만 쓴다.
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).willReturn(false)
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willReturn(values)
        val lock = lock(redis)
        val key = "order:create-lock:terminal:client-order"
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.submit { lock.tryAcquire(key) }
            Thread.sleep(600) // 재시도 간격이 상한(100ms, 지터로 50~100ms)까지 늘게 둔다
            val before = mockingDetails(values).invocations.size
            repeat(10) {
                lock.onReleased(key)
                Thread.sleep(10)
            }
            val retried = mockingDetails(values).invocations.size - before

            // 간격으로만 다시 시도하면 이 100ms 동안 많아야 두 번이다.
            assertTrue(retried >= 5, "알림 열 번에 다시 시도한 횟수: $retried")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `기다림이 끝나면 얻었든 포기했든 대기열에서 빠진다`() {
        @Suppress("UNCHECKED_CAST") // mock(ValueOperations::class.java)는 타입 인자를 모른다. 이 테스트는 String 키·값만 쓴다.
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).willReturn(false, true).willReturn(false)
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willReturn(values)
        val lock = lock(redis)
        val key = "order:create-lock:terminal:client-order"

        assertNotNull(lock.tryAcquire(key))
        val afterAcquired = lock.waitingCount(key)
        assertNull(lock.tryAcquire(key))

        assertEquals(0, afterAcquired)
        assertEquals(0, lock.waitingCount(key))
    }

    @Test
    fun `커넥션 팩토리가 닫혀도 던지지 않고 토큰 없이 돌아온다`() {
        assertNull(lock(closedRedis()).tryAcquire("test:lock"))
        assertEquals(1.0, failOpenCount("test:lock", "error"))
    }

    @Test
    fun `커넥션 팩토리가 닫혀도 해제는 던지지 않는다`() {
        lock(closedRedis()).release("test:lock", "token")
    }

    @Test
    fun `metric의 lock은 키의 앞 두 마디다`() {
        assertEquals("payment:unknown-resolve", RedisDistributedLock.lockName("payment:unknown-resolve"))
        assertEquals("payment:checkout-lock", RedisDistributedLock.lockName("payment:checkout-lock:order:2"))
    }

    /** 종료 중인 인스턴스처럼 모든 호출이 `LettuceConnectionFactory`의 닫힘 예외를 던진다. */
    private fun closedRedis(): StringRedisTemplate =
        mock(StringRedisTemplate::class.java) { throw IllegalStateException("LettuceConnectionFactory was destroyed") }

    @Test
    fun `잠그지 못하면 잠금 없이 블록을 실행하고 풀지 않는다`() {
        Tx.initialize(TxRunner())
        Lock.initialize(object : DistributedLock {
            override fun tryAcquire(key: String): String? = null
            override fun release(key: String, token: String) = error("잠그지 않았으니 풀 것도 없다")
        })

        assertEquals("done", Lock.executeWithLock("test:lock") { "done" })
    }
}
