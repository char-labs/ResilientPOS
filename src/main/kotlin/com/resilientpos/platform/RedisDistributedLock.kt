package com.resilientpos.platform

import com.resilientpos.foundation.IdentifierGenerator
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * [DistributedLock]의 Redis 구현. `SET key token NX PX`로 얻고, 토큰이 자기 것일 때만 지운다.
 *
 * 토큰을 확인하는 이유: TTL이 먼저 만료되어 다른 요청이 같은 키를 얻었으면 그 잠금을 지우면 안 된다.
 * TTL은 해제가 불리지 않은 경우(프로세스가 죽음)의 상한이다. 락 블록은 외부 호출 없는 짧은 트랜잭션이다.
 *
 * Redis 오류로 보는 예외는 `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)뿐이다.
 * 그 밖의 예외는 프로그래밍 오류라 삼키지 않는다.
 * 잠그지 못하고 진행할 때마다 counter `lock.fail.open{lock, reason}`이 오른다. 처음 일어날 때 생기므로 미리 등록하지 않는다
 * (업무 모듈의 키를 `platform`이 모른다).
 *
 * 시도마다 Timer `distributed.lock.wait{lock, outcome}`에 얻기까지 걸린 시간을 남긴다. `outcome`은 첫 SET에 얻은 `acquired`,
 * 다시 시도해서 얻은 `waited`, 잠금 없이 진행한 `timeout`·`error`다. 얻은 잠금은 Timer `distributed.lock.held{lock}`에 풀 때까지의
 * 시간을 남긴다. 대기 한도·TTL을 정하고 Redisson이 필요해지는지 보는 근거다(용량 기준 wiki).
 *
 * 대기 한도는 다시 시도하기를 멈추는 시각이다. 마지막 SET이 Redis 명령 timeout(`spring.data.redis.timeout`)까지 걸리면 그만큼 더 늦게 돌아온다.
 *
 * 기다리는 요청은 해제 알림으로 깨어 바로 다시 시도한다. 해제 스크립트가 지운 키를 [RELEASED_CHANNEL]로 PUBLISH하고(왕복은 한 번 그대로),
 * [LockReleaseListener]가 받아 [onReleased]를 부른다. 알림 하나는 그 pod 대기열의 맨 앞 하나만 깨운다. 기다림이 간격으로 끝나 다시 시도한
 * 요청이나 깨어났지만 SET을 빼앗긴 요청은 줄 뒤에 다시 서므로, 먼저 온 순서를 보장하지 않는다.
 * 알림은 놓칠 수 있다(대기 등록 전의 해제, TTL 만료, 구독이 끊긴 동안, 깨운 요청이 막 포기한 때). 그때는 재시도 간격이 지나 다시 시도한다.
 */
@Component
class RedisDistributedLock(
    private val redis: StringRedisTemplate,
    private val identifierGenerator: IdentifierGenerator,
    private val meterRegistry: MeterRegistry,
) : DistributedLock {

    /** 얻은 잠금의 토큰별 얻은 시각(nanoTime). [release]가 꺼내 지운다. `Lock`이 `finally`에서 늘 풀므로 쌓이지 않는다. */
    private val acquiredAt = ConcurrentHashMap<String, Long>()

    /** 키마다 해제를 기다리는 요청(먼저 온 순서). 기다림이 끝나면 각자 빠지므로 크기는 지금 기다리는 요청 수로 묶인다. */
    private val waiters = ConcurrentHashMap<String, ConcurrentLinkedQueue<CountDownLatch>>()

    override fun tryAcquire(key: String): String? {
        val started = System.nanoTime()
        val token = identifierGenerator.next().toString()
        val attempts = try {
            acquire(key, token)
        } catch (e: DataAccessException) {
            return proceedWithoutLock(key, e, started)
        } catch (e: IllegalStateException) {
            return proceedWithoutLock(key, e, started)
        }
        if (attempts == null) {
            recordWait(key, "timeout", started)
            log.warn { "잠금 없이 진행한다(대기 ${WAIT.toMillis()}ms 초과) key=$key" }
            countFailOpen(key, "timeout")
            return null
        }
        acquiredAt[token] = recordWait(key, if (attempts == 1) "acquired" else "waited", started)
        return token
    }

    override fun release(key: String, token: String) {
        acquiredAt.remove(token)?.let {
            meterRegistry.timer(HELD_METRIC, "lock", lockName(key)).record(System.nanoTime() - it, TimeUnit.NANOSECONDS)
        }
        val released = try {
            redis.execute(RELEASE, listOf(key), token, RELEASED_CHANNEL)
        } catch (e: DataAccessException) {
            return releaseFailed(key, e)
        } catch (e: IllegalStateException) {
            return releaseFailed(key, e)
        }
        if (released == 0L) log.warn { "잠금이 풀기 전에 만료됐다(블록이 TTL ${TTL.toSeconds()}초를 넘김) key=$key" }
    }

    /**
     * 해제 알림을 받은 키의 대기열 맨 앞 하나만 깨운다. 모두 깨우면 해제마다 기다리는 수만큼 SET이 몰려,
     * 같은 키 20개 동시에서 SET이 고정 간격 스핀보다도 많았다(용량 기준 wiki).
     * Lettuce I/O 스레드에서 불리므로 맵 잠금 없이 꺼낸다. 빈 대기열은 꺼내진 요청이 기다림을 끝내며 지운다.
     */
    fun onReleased(key: String) {
        waiters[key]?.poll()?.countDown()
    }

    /** 그 키를 기다리는 요청 수. 기다림이 끝나면 0으로 돌아와야 한다. */
    internal fun waitingCount(key: String): Int = waiters[key]?.size ?: 0

    /** 얻기까지 걸린 시간을 남기고 지금 시각을 돌려준다. */
    private fun recordWait(key: String, outcome: String, started: Long): Long {
        val now = System.nanoTime()
        meterRegistry.timer(WAIT_METRIC, "lock", lockName(key), "outcome", outcome).record(now - started, TimeUnit.NANOSECONDS)
        return now
    }

    private fun proceedWithoutLock(key: String, e: RuntimeException, started: Long): String? {
        recordWait(key, "error", started)
        log.warn(e) { "잠금 없이 진행한다(Redis 오류) key=$key" }
        countFailOpen(key, "error")
        return null
    }

    private fun countFailOpen(key: String, reason: String) =
        meterRegistry.counter(FAIL_OPEN_METRIC, "lock", lockName(key), "reason", reason).increment()

    private fun releaseFailed(key: String, e: RuntimeException) =
        log.warn(e) { "잠금을 풀지 못했다. TTL ${TTL.toSeconds()}초 뒤 사라진다 key=$key" }

    /** 얻으면 SET을 부른 횟수, 대기 한도 안에 얻지 못하면 null이다. 마지막 SET은 대기 한도에 부른다. */
    private fun acquire(key: String, token: String): Int? {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var attempts = 0
        var interval = MIN_RETRY_INTERVAL.toNanos()
        while (true) {
            attempts++
            if (redis.opsForValue().setIfAbsent(key, token, TTL) == true) return attempts
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            awaitRelease(key, minOf(jittered(interval), remaining))
            interval = minOf(interval * 2, MAX_RETRY_INTERVAL.toNanos())
        }
    }

    /** 해제 알림이 오거나 [timeoutNanos]가 지날 때까지 기다린다. */
    private fun awaitRelease(key: String, timeoutNanos: Long) {
        val released = CountDownLatch(1)
        waiters.compute(key) { _, queue -> (queue ?: ConcurrentLinkedQueue()).apply { add(released) } }
        try {
            released.await(timeoutNanos, TimeUnit.NANOSECONDS)
        } finally {
            waiters.computeIfPresent(key) { _, queue -> queue.apply { remove(released) }.ifEmpty { null } }
        }
    }

    /** 간격의 절반과 전체 사이(equal jitter). 같이 기다리는 요청들이 같은 순간에 SET하지 않게 흩는다. */
    private fun jittered(interval: Long): Long = ThreadLocalRandom.current().nextLong(interval / 2, interval + 1)

    companion object {
        private val log by logger()

        /** 잠그지 못하고 진행한 횟수. `lock`은 [lockName], `reason`은 `timeout`(대기 초과)이나 `error`(Redis 오류)다. */
        private const val FAIL_OPEN_METRIC = "lock.fail.open"
        private const val WAIT_METRIC = "distributed.lock.wait"
        private const val HELD_METRIC = "distributed.lock.held"

        /** 키의 앞 두 마디(`모듈:이름`). 뒤 마디는 식별자라 metric label로 쓰지 않는다([Lock]의 키 규칙). */
        fun lockName(key: String): String = key.split(':').take(2).joinToString(":")

        /** 견딜 수 있는 부하에서 잰 보유 시간 p99.9(최대 0.5초)의 3배를 0.5초 단위로 올린 값이다(용량 기준 wiki). 행 잠금 대기(3초)보다 짧다. */
        private val WAIT: Duration = Duration.ofMillis(1500)
        private val TTL: Duration = Duration.ofSeconds(10)
        /**
         * 재시도 간격은 [MIN_RETRY_INTERVAL]에서 두 배씩 [MAX_RETRY_INTERVAL]까지 는다. 첫 간격은 보유 시간 p50(1~7ms)에 맞춰 짧은 대기를 빨리 끝내고,
         * 상한은 같은 키를 여럿이 오래 기다릴 때 SET이 Redis 명령 지연을 늘리지 않게 묶는다(같은 키 20개 동시에서 10ms 고정 간격은 SET p99를 5배 넘게 올렸다, 용량 기준 wiki).
         */
        private val MIN_RETRY_INTERVAL: Duration = Duration.ofMillis(10)
        private val MAX_RETRY_INTERVAL: Duration = Duration.ofMillis(100)

        /** 해제 알림 채널. 메시지는 지운 키다. 키마다 채널을 두지 않아 구독을 늘리고 줄이는 왕복이 없고, 모든 pod가 모든 해제를 받는다. */
        const val RELEASED_CHANNEL = "platform:lock-released"

        private val RELEASE: RedisScript<Long> = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('del', KEYS[1]) redis.call('publish', ARGV[2], KEYS[1]) return 1 else return 0 end",
            Long::class.java,
        )
    }
}
