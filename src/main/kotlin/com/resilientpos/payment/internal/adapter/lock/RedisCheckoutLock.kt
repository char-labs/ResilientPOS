package com.resilientpos.payment.internal.adapter.lock

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.internal.domain.CheckoutLock
import com.resilientpos.platform.logger
import java.time.Duration
import java.util.UUID
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [CheckoutLock]의 Redis 구현. `SET key token NX PX`로 얻고, 트랜잭션이 끝나면 토큰이 자기 것일 때만 지운다.
 *
 * 토큰을 확인하는 이유: TTL이 먼저 만료되어 다른 요청이 같은 키를 얻었으면 그 잠금을 지우면 안 된다.
 * TTL은 해제가 불리지 않은 경우(프로세스가 죽음)의 상한이다. `createAttempt`는 provider를 부르지 않는 짧은 트랜잭션이다.
 *
 * Redis 오류나 대기 초과면 경고만 남기고 잠그지 않는다(fail-open). DB unique가 방어선이라 결제를 멈출 이유가 없다.
 *
 * 트랜잭션 동기화가 없는 호출은 해제를 거는 `registerSynchronization`이 `IllegalStateException`으로 거절한다.
 * 잠금을 얻은 뒤라 그 키는 TTL까지 남는다. 프로그래밍 오류에서만 생긴다.
 */
@Component
class RedisCheckoutLock(
    private val redis: StringRedisTemplate,
    private val identifierGenerator: IdentifierGenerator,
) : CheckoutLock {

    override fun lockUntilTransactionEnds(orderId: UUID, checkoutNo: Int) {
        val key = "payment:checkout-lock:$orderId:$checkoutNo"
        val token = identifierGenerator.next().toString()

        val acquired = try {
            acquire(key, token)
        } catch (e: DataAccessException) {
            log.warn(e) { "checkout 잠금 없이 진행한다(Redis 오류) orderId=$orderId checkoutNo=$checkoutNo" }
            return
        }
        if (!acquired) {
            log.warn { "checkout 잠금 없이 진행한다(대기 ${WAIT.toMillis()}ms 초과) orderId=$orderId checkoutNo=$checkoutNo" }
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) = release(key, token)
        })
    }

    private fun acquire(key: String, token: String): Boolean {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (true) {
            if (redis.opsForValue().setIfAbsent(key, token, TTL) == true) return true
            if (System.nanoTime() >= deadline) return false
            // ponytail: 폴링이다. 대기가 흔해지면 Redis pub/sub로 해제를 알린다.
            Thread.sleep(RETRY_INTERVAL.toMillis())
        }
    }

    /** 커밋 뒤라 여기서 던지면 호출자가 성공을 실패로 본다. 지우지 못한 잠금은 TTL이 치운다. */
    private fun release(key: String, token: String) {
        try {
            redis.execute(RELEASE, listOf(key), token)
        } catch (e: DataAccessException) {
            log.warn(e) { "checkout 잠금을 풀지 못했다. TTL ${TTL.toSeconds()}초 뒤 사라진다 key=$key" }
        }
    }

    companion object {
        private val log by logger()

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
