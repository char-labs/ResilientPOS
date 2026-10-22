package com.resilientpos.platform

import com.resilientpos.awaitUntil
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis 없이 뜬 앱도 Redis가 돌아오면 해제 알림을 받아야 한다. 구독하지 못한 동안 락은 재시도 간격으로만 기다린다.
 */
class LockReleaseListenerTest {

    @Test
    fun `기동 때 Redis가 없어 구독하지 못하면 간격을 두고 다시 구독한다`() {
        val connections = AtomicInteger()
        val factory = mock(RedisConnectionFactory::class.java)
        given(factory.connection).willAnswer {
            connections.incrementAndGet()
            throw RedisConnectionFailureException("Redis down")
        }
        val lock = RedisDistributedLock(mock(StringRedisTemplate::class.java), UuidV7IdentifierGenerator(), SimpleMeterRegistry())
        val listener = LockReleaseListener(factory, lock)

        try {
            listener.onApplicationEvent(mock(ApplicationReadyEvent::class.java))

            awaitUntil("구독을 다시 시도한다") { connections.get() >= 2 }
        } finally {
            listener.destroy()
        }
    }
}
