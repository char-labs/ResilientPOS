package com.resilientpos.owner.internal.adapter.redis

import com.resilientpos.owner.internal.domain.LoginFailureCounter
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.platform.SessionStoreUnavailableException
import com.resilientpos.platform.logger
import java.security.MessageDigest
import java.time.Duration
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * [LoginFailureCounter]의 Redis 구현. 키는 `owner:login-failure:<이메일 SHA-256 hex>`이고 TTL이 없을 때 15분 TTL을 건다(Lua 스크립트 하나로 원자적, 첫 시도가 곧 창의 시작).
 * 계정 존재와 무관하게 센다. 정규화한 이메일을 해시해 키에 평문 이메일을 남기지 않는다.
 *
 * Redis 오류로 보는 예외는 `DataAccessException`과 `IllegalStateException`(종료 중이라 닫힌 커넥션 팩토리)뿐이다.
 * [increment]의 오류는 [SessionStoreUnavailableException]으로 던진다(fail-closed). 시도를 셀 수 없으면 비밀번호를 비교하지 않아 무제한 대입을 열지 않는다.
 * [clear]는 로그인 성공 뒤라 WARN만 남기고 진행한다. 카운터가 창 끝까지 남을 뿐이다.
 * 그 밖의 예외는 프로그래밍 오류라 삼키지 않는다.
 */
@Component
class RedisLoginFailureCounter(private val redis: StringRedisTemplate) : LoginFailureCounter {

    override fun increment(email: OwnerEmail): Long = try {
        redis.execute(INCREMENT, listOf(key(email)), WINDOW.toMillis().toString()) ?: 0L
    } catch (e: DataAccessException) {
        throw SessionStoreUnavailableException(e)
    } catch (e: IllegalStateException) {
        throw SessionStoreUnavailableException(e)
    }

    override fun clear(email: OwnerEmail) {
        try {
            redis.delete(key(email))
        } catch (e: DataAccessException) {
            warnClearFailed(email, e)
        } catch (e: IllegalStateException) {
            warnClearFailed(email, e)
        }
    }

    private fun warnClearFailed(email: OwnerEmail, e: RuntimeException) =
        log.warn(e) { "로그인 성공 뒤 실패 횟수를 지우지 못했다(Redis 오류) key=${key(email)}" }

    private fun key(email: OwnerEmail): String =
        KEY_PREFIX + MessageDigest.getInstance("SHA-256").digest(email.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val log by logger()
        const val KEY_PREFIX = "owner:login-failure:"
        val WINDOW: Duration = Duration.ofMinutes(15)

        /** INCR과 PEXPIRE를 한 번에 한다. TTL이 없는 키는 어떤 경로로 생겼든 다음 시도에서 TTL을 얻어 영구 잠금이 되지 않는다. */
        val INCREMENT: RedisScript<Long> = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return n",
            Long::class.java,
        )
    }
}
