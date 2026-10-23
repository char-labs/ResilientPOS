package com.resilientpos.owner.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.owner.OwnerFixtures
import com.resilientpos.owner.OwnerManagement
import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.platform.UnauthenticatedException
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

@ExtendWith(OutputCaptureExtension::class)
class OwnerManagementIntegrationTest(
    private val owners: OwnerManagement,
    private val jdbcTemplate: JdbcTemplate,
    private val redis: StringRedisTemplate,
) : PostgresIntegrationTest() {

    private val password = "correct-horse-battery"
    private val email = "owner-${UUID.randomUUID()}@example.com"

    private fun failureKey(normalizedEmail: String) =
        "owner:login-failure:" + MessageDigest.getInstance("SHA-256").digest(normalizedEmail.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `가입하면 정규화한 이메일로 저장하고 해시는 {bcrypt} 접두어이며 평문이 없다`() {
        val result = owners.signUp(OwnerFixtures.signUpCommand("  ${email.uppercase()}  ", password))

        assertEquals(email, result.email)
        val hash = jdbcTemplate.queryForObject<String>("select password_hash from t_owner where id = ?", result.ownerId.value).let(::assertNotNull)
        assertTrue(hash.startsWith("{bcrypt}"), hash)
        assertTrue(!hash.contains(password))
        assertEquals(email, jdbcTemplate.queryForObject<String>("select email from t_owner where id = ?", result.ownerId.value))
    }

    @Test
    fun `대소문자만 다른 같은 이메일은 OWNER_EMAIL_DUPLICATED이고 계정이 늘지 않으며 이메일과 비밀번호가 출력에 남지 않는다`(output: CapturedOutput) {
        val mark = output.all.length // 이 테스트가 낸 출력만 본다. 클래스 단위 캡처에는 앞 테스트의 출력도 남는다
        owners.signUp(OwnerFixtures.signUpCommand(email, password))

        val e = assertFailsWith<DomainException> { owners.signUp(OwnerFixtures.signUpCommand(email.uppercase(), password)) }

        assertEquals(Owner.OWNER_EMAIL_DUPLICATED, e.code)
        assertEquals(DomainErrorKind.CONFLICT, e.kind)
        assertEquals(1, jdbcTemplate.queryForObject<Long>("select count(*) from t_owner where email = ?", email))
        assertFalse(output.all.substring(mark).contains(email), "unique 위반 상세의 이메일이 출력에 남았다")
        assertFalse(output.all.substring(mark).contains(password), "평문 비밀번호가 출력에 남았다")
    }

    @Test
    fun `로그인은 맞는 비밀번호에 점주 id를 돌려주고 틀린 비밀번호와 없는 계정은 UnauthenticatedException이다`() {
        val signedUp = owners.signUp(OwnerFixtures.signUpCommand(email, password))

        assertEquals(signedUp.ownerId, owners.logIn(OwnerFixtures.logInCommand(email.uppercase(), password)).ownerId)
        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand(email, "wrong-password-value")) }
        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand("nobody-$email", password)) }
    }

    @Test
    fun `실패는 이메일 SHA-256 키의 Redis 카운터에 15분 TTL로 쌓이고 5번이면 맞는 비밀번호도 거절하며 성공하면 지운다`() {
        val signedUp = owners.signUp(OwnerFixtures.signUpCommand(email, password))
        val key = failureKey(email)

        repeat(4) { assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand(email, "wrong-password-value")) } }
        assertEquals("4", redis.opsForValue().get(key))
        val ttl = assertNotNull(redis.getExpire(key))
        assertTrue(ttl in (Duration.ofMinutes(14).seconds)..(Duration.ofMinutes(15).seconds), "ttl=$ttl")

        assertEquals(signedUp.ownerId, owners.logIn(OwnerFixtures.logInCommand(email, password)).ownerId)
        assertNull(redis.opsForValue().get(key))

        repeat(5) { assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand(email, "wrong-password-value")) } }
        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand(email, password)) }
        assertEquals("6", redis.opsForValue().get(key), "한도를 넘긴 시도도 센다")
    }

    @Test
    fun `증가 뒤 키에는 시도마다 TTL이 있다`() {
        owners.signUp(OwnerFixtures.signUpCommand(email, password))
        val key = failureKey(email)

        repeat(7) {
            assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand(email, "wrong-password-value")) }
            assertTrue(redis.getExpire(key) > 0, "attempt=${it + 1} ttl=${redis.getExpire(key)}")
        }
    }

    @Test
    fun `TTL 없이 남은 키는 다음 시도에서 TTL을 얻어 영구 잠금이 되지 않는다`() {
        owners.signUp(OwnerFixtures.signUpCommand(email, password))
        val key = failureKey(email)
        redis.opsForValue().set(key, "9")

        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerFixtures.logInCommand(email, password)) }

        assertTrue(redis.getExpire(key) > 0, "ttl=${redis.getExpire(key)}")
    }

    @Test
    fun `카운터 4에서 같은 이메일로 맞는 비밀번호 로그인을 동시에 보내도 성공은 하나다`() {
        owners.signUp(OwnerFixtures.signUpCommand(email, password))
        redis.opsForValue().set(failureKey(email), "4", Duration.ofMinutes(15))
        val parallel = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(parallel)
        try {
            val results = (1..parallel).map {
                pool.submit<Boolean> {
                    start.await()
                    runCatching { owners.logIn(OwnerFixtures.logInCommand(email, password)) }.isSuccess
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }
}
