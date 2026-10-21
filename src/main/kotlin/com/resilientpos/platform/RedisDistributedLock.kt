package com.resilientpos.platform

import com.resilientpos.foundation.IdentifierGenerator
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
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
 */
@Component
class RedisDistributedLock(
    private val redis: StringRedisTemplate,
    private val identifierGenerator: IdentifierGenerator,
    private val meterRegistry: MeterRegistry,
) : DistributedLock {

    override fun tryAcquire(key: String): String? {
        val token = identifierGenerator.next().toString()
        val acquired = try {
            acquire(key, token)
        } catch (e: DataAccessException) {
            return proceedWithoutLock(key, e)
        } catch (e: IllegalStateException) {
            return proceedWithoutLock(key, e)
        }
        if (!acquired) {
            log.warn { "잠금 없이 진행한다(대기 ${WAIT.toMillis()}ms 초과) key=$key" }
            countFailOpen(key, "timeout")
            return null
        }
        return token
    }

    override fun release(key: String, token: String) {
        val released = try {
            redis.execute(RELEASE, listOf(key), token)
        } catch (e: DataAccessException) {
            return releaseFailed(key, e)
        } catch (e: IllegalStateException) {
            return releaseFailed(key, e)
        }
        if (released == 0L) log.warn { "잠금이 풀기 전에 만료됐다(블록이 TTL ${TTL.toSeconds()}초를 넘김) key=$key" }
    }

    private fun proceedWithoutLock(key: String, e: RuntimeException): String? {
        log.warn(e) { "잠금 없이 진행한다(Redis 오류) key=$key" }
        countFailOpen(key, "error")
        return null
    }

    private fun countFailOpen(key: String, reason: String) =
        meterRegistry.counter(FAIL_OPEN_METRIC, "lock", lockName(key), "reason", reason).increment()

    private fun releaseFailed(key: String, e: RuntimeException) =
        log.warn(e) { "잠금을 풀지 못했다. TTL ${TTL.toSeconds()}초 뒤 사라진다 key=$key" }

    private fun acquire(key: String, token: String): Boolean {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (true) {
            if (redis.opsForValue().setIfAbsent(key, token, TTL) == true) return true
            if (System.nanoTime() >= deadline) return false
            // ponytail: 폴링이다. 대기가 흔해지면 Redis pub/sub로 해제를 알린다.
            Thread.sleep(RETRY_INTERVAL.toMillis())
        }
    }

    companion object {
        private val log by logger()

        /** 잠그지 못하고 진행한 횟수. `lock`은 [lockName], `reason`은 `timeout`(대기 초과)이나 `error`(Redis 오류)다. */
        private const val FAIL_OPEN_METRIC = "lock.fail.open"

        /** 키의 앞 두 마디(`모듈:이름`). 뒤 마디는 식별자라 metric label로 쓰지 않는다([Lock]의 키 규칙). */
        fun lockName(key: String): String = key.split(':').take(2).joinToString(":")

        /** 행 잠금 대기(`findByIdForUpdate`)와 같은 3초다. */
        private val WAIT: Duration = Duration.ofSeconds(3)
        private val TTL: Duration = Duration.ofSeconds(10)
        private val RETRY_INTERVAL: Duration = Duration.ofMillis(50)

        private val RELEASE: RedisScript<Long> = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }
}
