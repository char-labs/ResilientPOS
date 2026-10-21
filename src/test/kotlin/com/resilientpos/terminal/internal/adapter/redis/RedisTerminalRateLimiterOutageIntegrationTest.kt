package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer

/**
 * 실제 Redis를 멈춰 fail-open이 timeout(1초)을 기다리지 않고 바로 일어나는지 본다. 설정 값 확인만으로는 끊김 동작을 증명하지 못한다.
 * 앱의 Lettuce 클라이언트 설정(`REJECT_COMMANDS`, timeout 1초)을 그대로 쓰되 이 테스트만의 Redis 컨테이너에 붙인다. 공용 컨테이너를 멈추면 다른 테스트가 깨진다.
 */
class RedisTerminalRateLimiterOutageIntegrationTest(
    @Autowired private val appRedis: LettuceConnectionFactory,
) : PostgresIntegrationTest() {

    @Test
    fun `Redis가 내려가면 timeout을 기다리지 않고 바로 통과한다`() {
        val redis = GenericContainer("redis:8-alpine").withExposedPorts(6379).apply { start() }
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)), appRedis.clientConfiguration)
            .apply { afterPropertiesSet(); start() }
        try {
            val limiter = RedisTerminalRateLimiter(StringRedisTemplate(factory), SimpleMeterRegistry(), 10, 1.0, 10, 1.0)
            val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID()))
            limiter.acquire(terminal) // 공유 연결을 맺는다

            redis.stop()

            // 끊긴 연결에 명령을 받아 두면(Lettuce 기본값) timeout 1초를 다 기다린 뒤에야 통과한다.
            val elapsed = measureTime { limiter.acquire(terminal) }
            assertTrue(elapsed < 500.milliseconds, "fail-open에 $elapsed 걸렸다")
        } finally {
            factory.destroy()
            redis.stop()
        }
    }
}
