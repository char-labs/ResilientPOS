package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer

/**
 * 실제 Redis를 멈추거나 얼려 fail-open이 command timeout(1초)을 계속 기다리지 않는지 본다. 설정 값 확인만으로는 끊김 동작을 증명하지 못한다.
 * 앱의 Lettuce 클라이언트 설정(`REJECT_COMMANDS`, `requestQueueSize`, timeout 1초)으로 이 테스트만의 Redis 컨테이너에 붙는다. 공용 컨테이너를 멈추면 다른 테스트가 깨진다.
 */
@ExtendWith(OutputCaptureExtension::class)
class RedisTerminalRateLimiterOutageIntegrationTest(
    @Autowired private val appRedis: LettuceConnectionFactory,
) : PostgresIntegrationTest() {

    private val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID()))

    private fun withRedis(
        clientConfiguration: LettuceClientConfiguration,
        clock: Clock = Clock.systemUTC(),
        test: (GenericContainer<*>, RedisTerminalRateLimiter) -> Unit,
    ) {
        val redis = GenericContainer("redis:8-alpine").withExposedPorts(6379).apply { start() }
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)), clientConfiguration)
            .apply { afterPropertiesSet(); start() }
        try {
            val limiter = RedisTerminalRateLimiter(StringRedisTemplate(factory), SimpleMeterRegistry(), clock, 10, 1.0, 10, 1.0)
            limiter.acquire(terminal) // 공유 연결을 맺는다
            test(redis, limiter)
        } finally {
            factory.destroy()
            redis.stop()
        }
    }

    @Test
    fun `Redis가 내려가면 command timeout을 기다리지 않고 바로 통과한다`() {
        withRedis(appRedis.clientConfiguration) { redis, limiter ->
            redis.stop()

            // 끊긴 연결에 명령을 받아 두면(Lettuce 기본값) 첫 호출부터 timeout 1초를 다 기다린다.
            val elapsed = measureTime { repeat(3) { limiter.acquire(terminal) } }

            assertTrue(elapsed < 1.seconds, "세 번 통과에 $elapsed 걸렸다")
        }
    }

    @Test
    fun `응답 없는 Redis에서 timeout으로 실패하면 그 뒤 1초 안의 요청은 기다리지 않는다`() {
        withRedis(appRedis.clientConfiguration) { redis, limiter ->
            redis.dockerClient.pauseContainerCmd(redis.containerId).exec()
            try {
                limiter.acquire(terminal) // command timeout 1초를 기다린 뒤 통과한다

                // 창을 Redis를 부르기 전 시각으로 세면 이미 1초가 지나 이 요청도 1초를 기다린다.
                val elapsed = measureTime { limiter.acquire(terminal) }

                assertTrue(elapsed < 500.milliseconds, "timeout 직후 요청이 $elapsed 기다렸다")
            } finally {
                redis.dockerClient.unpauseContainerCmd(redis.containerId).exec()
            }
        }
    }

    @Test
    fun `응답 없는 Redis에서 queue가 차면 다음 요청은 timeout을 기다리지 않고 통과하고 Lettuce WARN을 남기지 않는다`(output: CapturedOutput) {
        val mark = output.all.length
        // 앱 설정에서 queue 상한만 2로 줄여, 응답 없는 명령 두 개가 쌓인 뒤의 동작을 본다. 앱 값(1000)은 ResourceLimitConfigurationTest가 고정한다.
        val app = appRedis.clientConfiguration
        val small = LettuceClientConfiguration.builder()
            .commandTimeout(app.commandTimeout)
            .clientOptions(app.clientOptions.orElseThrow().mutate().requestQueueSize(2).build())
            .build()
        // 1초 창을 넘기려고 호출 사이에 시계를 앞당긴다. 그래야 호출마다 Redis를 불러 응답 없는 명령이 쌓인다.
        val clock = MutableClock(Instant.now())
        withRedis(small, clock) { redis, limiter ->
            // 컨테이너를 얼리면 커널이 ACK를 계속 보내 연결은 살아 있고 응답만 오지 않는다. 응답 없는 Redis를 재현한다(패킷이 사라지는 단절과 stack 동작은 같다).
            redis.dockerClient.pauseContainerCmd(redis.containerId).exec()
            try {
                repeat(2) {
                    limiter.acquire(terminal) // timeout 1초 뒤 통과하고, 응답 없는 명령으로 남는다
                    clock.advanceMillis(1_100)
                }

                // 상한이 없으면(Lettuce 기본값) 이것도 1초를 기다린다.
                val elapsed = measureTime { limiter.acquire(terminal) }

                assertTrue(elapsed < 500.milliseconds, "queue가 찬 뒤 통과에 $elapsed 걸렸다")
                // queue가 차면 Lettuce가 거절마다 스택이 붙은 WARN을 자기 로거로 남긴다. 그 로거는 application.yaml에서 ERROR로 올렸다.
                assertFalse(output.all.substring(mark).contains("Unexpected exception during request"), "Lettuce가 거절마다 WARN을 남겼다")
            } finally {
                redis.dockerClient.unpauseContainerCmd(redis.containerId).exec()
            }
        }
    }
}
