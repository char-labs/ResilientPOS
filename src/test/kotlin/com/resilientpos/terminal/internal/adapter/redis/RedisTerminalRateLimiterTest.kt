package com.resilientpos.terminal.internal.adapter.redis

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.internal.adapter.web.TerminalAuthenticationInterceptor
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import io.lettuce.core.RedisLoadingException
import io.lettuce.core.RedisReadOnlyException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.NestedExceptionUtils
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.StringRedisTemplate

/** Redis 응답을 흉내 내 스크립트 밖의 판단(장애 처리, 로그, 기동 검사, 설정 검증, metric)을 본다. 스크립트 자체는 `RedisTerminalRateLimiterIntegrationTest`가 본다. */
@ExtendWith(OutputCaptureExtension::class)
class RedisTerminalRateLimiterTest {

    private val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), StoreId(UUID.randomUUID()))
    private val meterRegistry = SimpleMeterRegistry()
    private val clock = MutableClock()

    private fun limiter(
        redis: StringRedisTemplate,
        terminalCapacity: Long = 10,
        terminalRefillPerSecond: Double = 1.0,
        storeCapacity: Long = 10,
        storeRefillPerSecond: Double = 1.0,
    ) = RedisTerminalRateLimiter(redis, meterRegistry, clock, terminalCapacity, terminalRefillPerSecond, storeCapacity, storeRefillPerSecond)

    /** 스크립트가 [scriptResult]를 돌려주는 Redis. */
    private fun redisReturning(scriptResult: List<Long>) = mock(StringRedisTemplate::class.java) { scriptResult }

    private fun redisThrowing(error: RuntimeException) = mock(StringRedisTemplate::class.java) { throw error }

    /** [mark] 뒤에 limiter가 남긴 줄. 장애 중에는 요청마다 부르므로 스택 프레임이 찍히면 로그가 장애를 키운다. */
    private fun CapturedOutput.limiterLines(mark: Int): List<String> {
        val logged = all.substring(mark)
        assertFalse(logged.contains("\tat "), "스택 프레임이 찍혔다")
        return logged.lines().filter { it.contains("제한 없이 통과시킨다") || it.contains("기동 검사") || it.contains("빈도 상한으로 거절했다") }
    }

    @Test
    fun `기다리면 풀리는 Redis 오류면 통과시키고 근본 원인을 담은 WARN 한 줄을 남긴다`(output: CapturedOutput) {
        listOf(
            RedisConnectionFailureException("Unable to connect to Redis") to "RedisConnectionFailureException: Unable to connect to Redis",
            IllegalStateException("LettuceConnectionFactory was destroyed") to "IllegalStateException: LettuceConnectionFactory was destroyed",
            // REJECT_COMMANDS로 끊긴 동안 거절된 명령. Spring 예외의 메시지("Redis exception")에는 이유가 없다.
            RedisSystemException("Redis exception", RedisException("Currently not connected. Commands are rejected."))
                to "RedisException: Currently not connected. Commands are rejected.",
            // Redis가 오류로 답했지만 재시작 중이라 기다리면 풀린다.
            RedisSystemException("Error in execution", RedisLoadingException("LOADING Redis is loading the dataset in memory"))
                to "RedisLoadingException: LOADING Redis is loading the dataset in memory",
            RedisSystemException("Error in execution", RedisCommandExecutionException("TRYAGAIN Multiple keys request during rehashing of slot"))
                to "RedisCommandExecutionException: TRYAGAIN Multiple keys request during rehashing of slot",
        ).forEach { (error, cause) ->
            val mark = output.all.length

            limiter(redisThrowing(error)).acquire(terminal)

            val line = output.limiterLines(mark).single()
            assertContains(line, "WARN")
            assertContains(line, "제한 없이 통과시킨다(Redis 오류)")
            assertContains(line, "terminalId=${terminal.terminalId.value}")
            assertContains(line, "cause=$cause")
        }
    }

    @Test
    fun `기다려도 풀리지 않는 Redis 오류면 통과시키되 근본 원인을 담은 ERROR 한 줄로 보호가 꺼졌음을 남긴다`(output: CapturedOutput) {
        listOf(
            RedisSystemException("Error in execution", RedisCommandExecutionException("ERR user_script:1: Script attempted to access nonexistent global variable"))
                to "RedisCommandExecutionException: ERR user_script:1",
            RedisSystemException("Error in execution", RedisCommandExecutionException("NOPERM User app has no permissions to run the 'time' command"))
                to "RedisCommandExecutionException: NOPERM",
            // 연결 단계의 인증 실패는 연결 실패로 감싸여 오지만 근본 원인은 Redis의 오류 응답이고 기다려도 풀리지 않는다.
            RedisConnectionFailureException(
                "Redis connection failed",
                RedisConnectionException("Unable to connect to localhost:6379", RedisCommandExecutionException("WRONGPASS invalid username-password pair")),
            ) to "RedisCommandExecutionException: WRONGPASS",
        ).forEach { (error, cause) ->
            val mark = output.all.length

            limiter(redisThrowing(error)).acquire(terminal)

            val line = output.limiterLines(mark).single()
            assertContains(line, "ERROR")
            assertContains(line, "Redis가 명령을 거절")
            assertContains(line, "cause=$cause")
        }
    }

    @Test
    fun `같은 수준의 장애 로그는 10초에 한 줄이고 그 사이 통과 건수를 다음 줄에 적는다`(output: CapturedOutput) {
        val mark = output.all.length
        val limiter = limiter(redisThrowing(RedisConnectionFailureException("Unable to connect to Redis")))
        repeat(3) { limiter.acquire(terminal) }
        clock.advanceSeconds(9)
        limiter.acquire(terminal)
        assertEquals(1, output.limiterLines(mark).size)

        clock.advanceSeconds(1)
        limiter.acquire(terminal)

        val lines = output.limiterLines(mark)
        assertEquals(2, lines.size)
        assertContains(lines[0], "suppressedSinceLast=0 lastLoggedAt=none")
        assertContains(lines[1], "suppressedSinceLast=3 lastLoggedAt=2026-10-21T00:00:00Z")
    }

    @Test
    fun `동시 요청의 완료 순서가 조금 뒤바뀐 것은 시계 역행으로 보지 않는다`(output: CapturedOutput) {
        val mark = output.all.length
        val results = ArrayDeque(listOf(listOf(1L, 500_000L), listOf(1L, 500_000L)))
        val limiter = limiter(mock(StringRedisTemplate::class.java) { results.removeFirst() })
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        // 먼저 시작해 늦게 끝난 요청의 시각은 앞 줄보다 조금 이르다.
        clock.advanceMillis(-5)
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        assertEquals(1, output.limiterLines(mark).size)
    }

    @Test
    fun `시계가 뒤로 가면 기다리지 않고 바로 남긴다`(output: CapturedOutput) {
        val mark = output.all.length
        val limiter = limiter(redisThrowing(RedisConnectionFailureException("Unable to connect to Redis")))
        limiter.acquire(terminal)

        // NTP step이나 VM 재개로 한 시간 뒤로 갔다. 그대로면 한 시간 10초 동안 아무 줄도 남지 않는다.
        clock.advanceSeconds(-3_600)
        limiter.acquire(terminal)

        assertEquals(2, output.limiterLines(mark).size)
    }

    @Test
    fun `연결 계열 실패 뒤 1초 동안은 Redis를 부르지 않고 통과하고 1초가 지나면 다시 부른다`() {
        var calls = 0
        val limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                throw RedisConnectionFailureException("Unable to connect to Redis")
            },
        )

        repeat(3) { limiter.acquire(terminal) }
        clock.advanceMillis(999)
        limiter.acquire(terminal)
        assertEquals(1, calls)

        clock.advanceMillis(1)
        limiter.acquire(terminal)

        assertEquals(2, calls)
        // 탐침이 실패해도 다음 창이 지나면 다시 부른다. 탐침 표시가 남으면 다음 탐침이 영영 없어 보호가 꺼진 채로 남는다.
        clock.advanceMillis(1_000)
        limiter.acquire(terminal)
        assertEquals(3, calls)
    }

    @Test
    fun `timeout으로 실패하면 실패를 본 시각부터 1초를 센다`() {
        var calls = 0
        val limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                // command timeout 1초를 다 기다린 뒤 실패한다.
                clock.advanceMillis(1_000)
                throw QueryTimeoutException("Redis command timed out")
            },
        )

        limiter.acquire(terminal)
        // 호출 전 시각으로 세면 이미 1초가 지나 다시 부르고 또 1초를 기다린다.
        limiter.acquire(terminal)

        assertEquals(1, calls)
    }

    @Test
    fun `창이 지나면 요청 하나만 Redis를 다시 불러 보고 그동안 온 요청은 건너뛴다`() {
        var calls = 0
        lateinit var limiter: RedisTerminalRateLimiter
        limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                // 탐침이 Redis를 기다리는 동안 다른 요청이 들어온다.
                if (calls == 2) limiter.acquire(terminal)
                throw RedisConnectionFailureException("Unable to connect to Redis")
            },
        )
        limiter.acquire(terminal)
        clock.advanceMillis(1_000)

        limiter.acquire(terminal)

        // 탐침 하나(2번째 호출)만 Redis를 불렀다. 그동안 온 요청까지 부르면 미연결 pod에서 연결 잠금에 줄을 선다.
        assertEquals(2, calls)
    }

    @Test
    fun `탐침이 성공하면 창을 닫아 그 뒤로는 동시 요청도 Redis를 부른다`() {
        var calls = 0
        lateinit var limiter: RedisTerminalRateLimiter
        limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                if (calls == 1) throw RedisConnectionFailureException("Unable to connect to Redis")
                // 창이 닫힌 뒤의 요청이 Redis를 기다리는 동안 다른 요청이 들어온다.
                if (calls == 3) limiter.acquire(terminal)
                listOf(0L, 0L)
            },
        )
        limiter.acquire(terminal)
        clock.advanceMillis(1_000)
        limiter.acquire(terminal)

        limiter.acquire(terminal)

        // 창이 남으면 지난 창 때문에 요청마다 탐침이 되어 Redis는 한 번에 하나만 부르고 동시 요청은 제한 없이 통과한다.
        assertEquals(4, calls)
    }

    @Test
    fun `탐침이 1초 넘게 걸려도 끝날 때까지 다른 요청은 건너뛴다`() {
        var calls = 0
        lateinit var limiter: RedisTerminalRateLimiter
        limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                // 탐침이 connect timeout(1초)에 지연이 더해져 1.5초째 기다리는 동안 다른 요청이 들어온다.
                if (calls == 2) {
                    clock.advanceMillis(1_500)
                    limiter.acquire(terminal)
                }
                throw RedisConnectionFailureException("Unable to connect to Redis")
            },
        )
        limiter.acquire(terminal)
        clock.advanceMillis(1_000)

        limiter.acquire(terminal)

        // 두 번째 탐침이 들면 미연결 pod의 공유 연결 잠금을 limiter가 쉬지 않고 쥔다.
        assertEquals(2, calls)
    }

    @Test
    fun `창이 열리기 전에 보낸 요청이 늦게 성공해도 창을 닫지 않는다`() {
        var calls = 0
        lateinit var limiter: RedisTerminalRateLimiter
        limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                when (calls) {
                    // 이 요청이 Redis를 기다리는 동안 다른 요청이 연결 실패로 창을 연다.
                    1 -> {
                        limiter.acquire(terminal)
                        listOf(0L, 0L)
                    }
                    2 -> throw RedisConnectionFailureException("Unable to connect to Redis")
                    else -> listOf(0L, 0L)
                }
            },
        )
        limiter.acquire(terminal)

        limiter.acquire(terminal)

        // 늦게 온 성공은 탐침이 아니라 막 열린 창을 닫지 못한다.
        assertEquals(2, calls)
    }

    @Test
    fun `탐침이 창을 열지 않는 오류를 받으면 창을 닫아 그 뒤로는 동시 요청도 Redis를 부른다`() {
        var calls = 0
        lateinit var limiter: RedisTerminalRateLimiter
        limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                when (calls) {
                    1 -> throw RedisConnectionFailureException("Unable to connect to Redis")
                    2 -> throw RedisSystemException("Error in execution", RedisCommandExecutionException("NOPERM no permissions"))
                    // 창이 닫힌 뒤의 요청이 Redis를 기다리는 동안 다른 요청이 들어온다.
                    3 -> {
                        limiter.acquire(terminal)
                        listOf(0L, 0L)
                    }
                    else -> listOf(0L, 0L)
                }
            },
        )
        limiter.acquire(terminal)
        clock.advanceMillis(1_000)
        limiter.acquire(terminal)

        limiter.acquire(terminal)

        // 명령 단계의 설정 오류는 Redis가 바로 답하므로 건너뛰지 않는다. 창이 남으면 동시 요청은 건너뛴다.
        assertEquals(4, calls)
    }

    @Test
    fun `창 판정은 1초 안의 시계 차이는 건너뛰고 그보다 크게 뒤로 가면 바로 다시 불러 본다`() {
        var calls = 0
        val limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                throw RedisConnectionFailureException("Unable to connect to Redis")
            },
        )
        limiter.acquire(terminal)

        // 시각을 읽은 직후 다른 요청이 실패 시각을 기록하면 차이가 조금 음수가 된다.
        clock.advanceMillis(-1)
        limiter.acquire(terminal)
        assertEquals(1, calls)

        // 크게 뒤로 간 시계를 기다리면 그만큼 Redis를 부르지 않아 보호가 꺼진다.
        clock.advanceSeconds(-3_600)
        limiter.acquire(terminal)
        assertEquals(2, calls)
    }

    @Test
    fun `창을 연 실패가 ERROR면 창 안에서 건너뛴 건수를 다음 ERROR 줄에 적는다`(output: CapturedOutput) {
        val mark = output.all.length
        val wrongPassword = RedisConnectionFailureException(
            "Redis connection failed",
            RedisConnectionException("Unable to connect", RedisCommandExecutionException("WRONGPASS invalid username-password pair")),
        )
        val limiter = limiter(redisThrowing(wrongPassword))

        limiter.acquire(terminal)
        repeat(3) { limiter.acquire(terminal) }
        clock.advanceSeconds(10)
        limiter.acquire(terminal)

        val lines = output.limiterLines(mark)
        assertEquals(2, lines.size, lines.joinToString("\n"))
        assertContains(lines[1], "ERROR")
        // 창 안에서 제한 없이 통과한 셋이 보호가 꺼진 규모로 같은 수준의 줄에 남는다.
        assertContains(lines[1], "suppressedSinceLast=3")
    }

    @Test
    fun `기동 검사가 Redis에 닿지 못하면 창을 연 채로 시작한다`() {
        var calls = 0
        val limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                throw RedisConnectionFailureException("Unable to connect to Redis")
            },
        )
        limiter.checkScriptAtStartup()

        // 첫 요청들이 공유 연결 잠금에 줄을 서며 connect timeout을 하나씩 기다리지 않는다.
        limiter.acquire(terminal)

        assertEquals(1, calls)
    }

    @Test
    fun `연결 단계의 오류 응답은 ERROR로 남기되 요청마다 다시 연결하지 않게 1초 창을 연다`() {
        var calls = 0
        val wrongPassword = RedisConnectionFailureException(
            "Redis connection failed",
            RedisConnectionException("Unable to connect", RedisCommandExecutionException("WRONGPASS invalid username-password pair")),
        )
        val limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                throw wrongPassword
            },
        )

        repeat(3) { limiter.acquire(terminal) }

        assertEquals(1, calls)
    }

    @Test
    fun `Redis가 명령에 설정 오류로 답한 뒤에는 건너뛰지 않는다`() {
        var calls = 0
        val limiter = limiter(
            mock(StringRedisTemplate::class.java) {
                calls++
                throw RedisSystemException("Error in execution", RedisCommandExecutionException("NOPERM no permissions"))
            },
        )

        repeat(3) { limiter.acquire(terminal) }

        assertEquals(3, calls)
    }

    @Test
    fun `거절 로그는 scope별로 10초에 한 줄이고 그 사이 건수와 직전 시각을 다음 줄에 적는다`(output: CapturedOutput) {
        val mark = output.all.length
        // 단말 bucket 거절 셋, 같은 시각의 매장 bucket 거절 하나, 10초 뒤 단말 bucket 거절 하나.
        val results = ArrayDeque(
            listOf(
                listOf(1L, 500_000L), listOf(1L, 500_000L), listOf(1L, 500_000L),
                listOf(2L, 2_000_000L),
                listOf(1L, 500_000L),
            ),
        )
        val limiter = limiter(mock(StringRedisTemplate::class.java) { results.removeFirst() })

        repeat(4) { assertFailsWith<RateLimitedException> { limiter.acquire(terminal) } }
        clock.advanceSeconds(10)
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal) }

        val lines = output.limiterLines(mark)
        assertEquals(3, lines.size, lines.joinToString("\n"))
        lines.forEach { assertContains(it, "INFO") }
        assertContains(lines[0], "scope=terminal terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value} retryAfterSeconds=1 suppressedSinceLast=0")
        assertContains(lines[1], "scope=store")
        assertContains(lines[1], "retryAfterSeconds=2 suppressedSinceLast=0")
        assertContains(lines[2], "scope=terminal")
        assertContains(lines[2], "suppressedSinceLast=2 lastLoggedAt=2026-10-21T00:00:00Z")
    }

    @Test
    fun `WARN과 ERROR는 따로 줄인다`(output: CapturedOutput) {
        val mark = output.all.length
        // 부를 때마다 다음 예외를 던진다.
        val errors = ArrayDeque(
            listOf(
                RedisConnectionFailureException("Unable to connect to Redis"),
                RedisSystemException("Error in execution", RedisCommandExecutionException("NOPERM no permissions")),
            ),
        )
        val limiter = limiter(mock(StringRedisTemplate::class.java) { throw errors.removeFirst() })

        limiter.acquire(terminal)
        // 연결 실패 뒤 1초는 Redis를 부르지 않으므로 그 창만큼 지난 뒤 부른다. 10초 간격 안이라 같은 수준이면 줄어든다.
        clock.advanceMillis(1_000)
        limiter.acquire(terminal)

        assertEquals(listOf("WARN", "ERROR"), output.limiterLines(mark).map { if (it.contains("ERROR")) "ERROR" else "WARN" })
    }

    @Test
    fun `기동 검사에서 Redis가 앱 쪽 설정 오류로 답하면 기동을 멈춘다`() {
        listOf(
            "NOPERM User app has no permissions to run the 'evalsha' command",
            "CROSSSLOT Keys in request don't hash to the same slot",
            "WRONGTYPE Operation against a key holding the wrong kind of value",
            "ERR user_script:1: Script attempted to access nonexistent global variable",
            "ERR Error compiling script (new function): user_script:1: unexpected symbol near 'local'",
            "ERR unknown command 'EVALSHA', with args beginning with:",
            // 스크립트 안에서 막힌 명령은 NOPERM이 아니라 이 문구로 온다. 앞은 Redis 7.2 이상, 뒤는 7.0이다(실측).
            "ERR ACL failure in script: User app has no permissions to run the 'hset' command script: bcd81f92, on @user_script:23.",
            "ERR The user executing the script can't run this command or subcommand script: bcd81f92, on @user_script:23.",
            "ERR Unknown Redis command called from script script: bcd81f92, on @user_script:1.",
        ).forEach { reply ->
            val limiter = limiter(redisThrowing(RedisSystemException("Error in execution", RedisCommandExecutionException(reply))))

            val e = assertFailsWith<IllegalStateException> { limiter.checkScriptAtStartup() }

            assertContains(e.message.orEmpty(), reply.substringBefore(' '))
            // 기동에 한 번 나는 실패라 원인 예외를 붙인다.
            assertTrue(e.cause is RedisSystemException)
        }
        // 연결 단계의 인증 실패도 앱 설정(비밀번호) 오류다.
        val wrongPassword = RedisConnectionFailureException(
            "Redis connection failed",
            RedisConnectionException("Unable to connect", RedisCommandExecutionException("WRONGPASS invalid username-password pair")),
        )
        assertFailsWith<IllegalStateException> { limiter(redisThrowing(wrongPassword)).checkScriptAtStartup() }
    }

    @Test
    fun `기동 검사에서 Redis에 닿지 못하거나 Redis 쪽 상태 오류면 WARN을 남기고 뜬다`(output: CapturedOutput) {
        listOf(
            RedisConnectionFailureException("Unable to connect to Redis"),
            RedisSystemException("Error in execution", RedisLoadingException("LOADING Redis is loading the dataset in memory")),
            // Redis 사고 중에도 다시 뜨는 pod와 앱 핫픽스가 막히면 안 된다.
            RedisSystemException("Error in execution", RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'.")),
            RedisSystemException("Error in execution", RedisReadOnlyException("READONLY You can't write against a read only replica.")),
            RedisSystemException("Error in execution", RedisCommandExecutionException("ERR max number of clients reached")),
        ).forEach { error ->
            val mark = output.all.length

            limiter(redisThrowing(error)).checkScriptAtStartup()

            val line = output.limiterLines(mark).single()
            assertContains(line, "WARN")
            assertContains(line, "기동 검사를 건너뛴다")
        }
    }

    @Test
    fun `기동 검사는 인스턴스마다 새 키로 통과 분기까지 돌리고 새 bucket이 거절되면 기동을 멈춘다`() {
        val calledKeys = mutableListOf<List<String>>()
        val redis = mock(StringRedisTemplate::class.java) { invocation ->
            calledKeys += invocation.getArgument<List<String>>(1)
            listOf(0L, 0L)
        }

        limiter(redis).checkScriptAtStartup()
        limiter(redis).checkScriptAtStartup()

        // 키를 나누면 앞선 기동이 비운 bucket에서 거절 분기만 돌아 HSET·PEXPIRE 권한 누락을 놓친다.
        val (first, second) = calledKeys
        assertNotEquals(first, second)
        calledKeys.forEach { (terminalKey, storeKey) ->
            val tag = terminalKey.substringAfter('{').substringBefore('}')
            assertTrue(tag.startsWith("preflight:"), terminalKey)
            assertEquals("rate-limit:{$tag}:store", storeKey)
        }
        // 새 bucket은 capacity가 1 이상이라 반드시 통과한다. 거절이면 스크립트가 잘못됐다.
        assertFailsWith<IllegalStateException> { limiter(redisReturning(listOf(1L, 1_000_000L))).checkScriptAtStartup() }
    }

    @Test
    fun `기동 검사는 결과 모양이 운영과 다르거나 Redis 밖의 오류면 삼키지 않는다`() {
        // 스크립트 반환 형식이 바뀌면 기동은 통과하고 모든 /v1이 500이 되는 대신 기동에서 드러난다.
        assertFailsWith<ClassCastException> { limiter(mock(StringRedisTemplate::class.java) { listOf("rejected", "wait") }).checkScriptAtStartup() }
        // 기동 시점에는 닫힌 커넥션 팩토리가 없다. 이 예외를 삼키면 검사가 조용히 꺼진다.
        val lifecycle = IllegalStateException("LettuceConnectionFactory has been STOPPED")
        assertSame(lifecycle, assertFailsWith<IllegalStateException> { limiter(redisThrowing(lifecycle)).checkScriptAtStartup() })
    }

    @Test
    fun `기동 검사는 빈을 만들 때 돌아 설정 오류면 컨텍스트가 뜨지 못한다`() {
        val noPermission = redisThrowing(RedisSystemException("Error in execution", RedisCommandExecutionException("NOPERM no permissions")))

        ApplicationContextRunner()
            .withBean(StringRedisTemplate::class.java, { noPermission })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(Clock::class.java, { clock })
            .withPropertyValues(
                "resilientpos.rate-limit.terminal.capacity=10", "resilientpos.rate-limit.terminal.refill-per-second=1",
                "resilientpos.rate-limit.store.capacity=10", "resilientpos.rate-limit.store.refill-per-second=1",
            )
            .withUserConfiguration(RedisTerminalRateLimiter::class.java)
            .run { context ->
                val failure = checkNotNull(context.startupFailure) { "설정 오류인데 컨텍스트가 떴다" }
                assertContains(NestedExceptionUtils.getMostSpecificCause(failure).message.orEmpty(), "NOPERM")
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
    fun `설정 오류 메시지는 어느 bucket의 어떤 값인지 적는다`() {
        val e = assertFailsWith<IllegalArgumentException> { limiter(redisReturning(listOf(0L, 0L)), storeCapacity = 0) }

        assertContains(e.message.orEmpty(), "rate-limit.store")
        assertContains(e.message.orEmpty(), "capacity=0")
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

    /**
     * 429·`RATE_LIMITED`는 상태를 바꾸기 전(handler 전)의 거절이어야 단말이 같은 요청을 그대로 다시 보낼 수 있다.
     * 그래서 limiter는 인증 인터셉터만 부른다. 양성 대조가 있어 `acquire`의 시그니처가 바뀌면 규칙이 비지 않고 실패한다.
     */
    @Test
    fun `acquire는 인증 인터셉터만 부른다`() {
        val production = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.resilientpos")
        // 시그니처가 아니라 이름과 소유 타입으로 맞춘다. 오버로드나 기본값 인자(합성 메서드)가 생겨도 규칙이 비지 않는다.
        val acquire = target(name("acquire")).and(target(owner(type(RedisTerminalRateLimiter::class.java))))

        noClasses().that().doNotBelongToAnyOf(TerminalAuthenticationInterceptor::class.java)
            .should().callMethodWhere(acquire)
            .check(production)
        classes().that().haveFullyQualifiedName(TerminalAuthenticationInterceptor::class.java.name)
            .should().callMethodWhere(acquire)
            .check(production)
    }

    private companion object {
        const val METRIC = "inbound.rate.limited"
    }
}
