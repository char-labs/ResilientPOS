package com.resilientpos.platform

import com.resilientpos.foundation.UuidV7IdentifierGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 잠금 저장소가 응답하지 않아도 일을 멈추지 않는지 본다. 방어선은 DB 제약이라서 잠금은 fail-open이다.
 */
class LockTest {

    @Test
    fun `Redis 오류면 던지지 않고 토큰 없이 돌아온다`() {
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willThrow(RedisConnectionFailureException("Redis down"))

        assertNull(RedisDistributedLock(redis, UuidV7IdentifierGenerator()).tryAcquire("test:lock"))
    }

    @Test
    fun `커넥션 팩토리가 닫혀도 던지지 않고 토큰 없이 돌아온다`() {
        assertNull(RedisDistributedLock(closedRedis(), UuidV7IdentifierGenerator()).tryAcquire("test:lock"))
    }

    @Test
    fun `커넥션 팩토리가 닫혀도 해제는 던지지 않는다`() {
        RedisDistributedLock(closedRedis(), UuidV7IdentifierGenerator()).release("test:lock", "token")
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
