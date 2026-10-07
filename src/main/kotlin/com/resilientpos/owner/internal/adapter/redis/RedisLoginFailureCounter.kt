package com.resilientpos.owner.internal.adapter.redis

import com.resilientpos.owner.internal.domain.LoginFailureCounter
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.platform.logger
import java.security.MessageDigest
import java.time.Duration
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * [LoginFailureCounter]의 Redis 구현. 키는 `owner:login-failure:<이메일 SHA-256 hex>`이고 첫 실패에 15분 TTL을 건다.
 * 계정 존재와 무관하게 센다. 정규화한 이메일을 해시해 키에 평문 이메일을 남기지 않는다.
 *
 * Redis 오류로 보는 예외는 `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)뿐이다.
 * 오류면 WARN만 남기고 제한 없이 진행한다(fail-open, `RedisDistributedLock`과 같은 정책).
 * 그 밖의 예외는 프로그래밍 오류라 삼키지 않는다.
 */
@Component
class RedisLoginFailureCounter(private val redis: StringRedisTemplate) : LoginFailureCounter {

    override fun count(email: OwnerEmail): Long =
        failOpen(email, 0) { redis.opsForValue().get(key(email))?.toLongOrNull() ?: 0 }

    override fun increment(email: OwnerEmail) {
        failOpen(email, Unit) {
            val key = key(email)
            if (redis.opsForValue().increment(key) == 1L) redis.expire(key, WINDOW)
        }
    }

    override fun clear(email: OwnerEmail) {
        failOpen(email, Unit) { redis.delete(key(email)) }
    }

    private fun <T> failOpen(email: OwnerEmail, fallback: T, action: () -> T): T = try {
        action()
    } catch (e: DataAccessException) {
        warn(email, e).let { fallback }
    } catch (e: IllegalStateException) {
        warn(email, e).let { fallback }
    }

    private fun warn(email: OwnerEmail, e: RuntimeException) =
        log.warn(e) { "로그인 실패 제한 없이 진행한다(Redis 오류) key=${key(email)}" }

    private fun key(email: OwnerEmail): String =
        KEY_PREFIX + MessageDigest.getInstance("SHA-256").digest(email.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val log by logger()
        const val KEY_PREFIX = "owner:login-failure:"
        val WINDOW: Duration = Duration.ofMinutes(15)
    }
}
