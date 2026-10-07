package com.resilientpos.owner.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.owner.OwnerCommand
import com.resilientpos.owner.OwnerManagement
import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.platform.UnauthenticatedException
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

class OwnerManagementIntegrationTest(
    @Autowired private val owners: OwnerManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val redis: StringRedisTemplate,
) : PostgresIntegrationTest() {

    private val password = "correct-horse-battery"
    private val email = "owner-${UUID.randomUUID()}@example.com"

    private fun failureKey(normalizedEmail: String) =
        "owner:login-failure:" + MessageDigest.getInstance("SHA-256").digest(normalizedEmail.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `가입하면 정규화한 이메일로 저장하고 해시는 {bcrypt} 접두어이며 평문이 없다`() {
        val result = owners.signUp(OwnerCommand.SignUp("  ${email.uppercase()}  ", password))

        assertEquals(email, result.email)
        val hash = jdbcTemplate.queryForObject<String>("select password_hash from t_owner where id = ?", result.ownerId.value).let(::assertNotNull)
        assertTrue(hash.startsWith("{bcrypt}"), hash)
        assertTrue(!hash.contains(password))
        assertEquals(email, jdbcTemplate.queryForObject<String>("select email from t_owner where id = ?", result.ownerId.value))
    }

    @Test
    fun `대소문자만 다른 같은 이메일은 OWNER_EMAIL_DUPLICATED이고 계정이 늘지 않는다`() {
        owners.signUp(OwnerCommand.SignUp(email, password))

        val e = assertFailsWith<DomainException> { owners.signUp(OwnerCommand.SignUp(email.uppercase(), password)) }

        assertEquals(Owner.OWNER_EMAIL_DUPLICATED, e.code)
        assertEquals(DomainErrorKind.CONFLICT, e.kind)
        assertEquals(1, jdbcTemplate.queryForObject<Long>("select count(*) from t_owner where email = ?", email))
    }

    @Test
    fun `로그인은 맞는 비밀번호에 점주 id를 돌려주고 틀린 비밀번호와 없는 계정은 UnauthenticatedException이다`() {
        val signedUp = owners.signUp(OwnerCommand.SignUp(email, password))

        assertEquals(signedUp.ownerId, owners.logIn(OwnerCommand.LogIn(email.uppercase(), password)))
        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerCommand.LogIn(email, "wrong-password-value")) }
        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerCommand.LogIn("nobody-$email", password)) }
    }

    @Test
    fun `실패는 이메일 SHA-256 키의 Redis 카운터에 15분 TTL로 쌓이고 5번이면 맞는 비밀번호도 거절하며 성공하면 지운다`() {
        val signedUp = owners.signUp(OwnerCommand.SignUp(email, password))
        val key = failureKey(email)

        repeat(4) { assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerCommand.LogIn(email, "wrong-password-value")) } }
        assertEquals("4", redis.opsForValue().get(key))
        val ttl = assertNotNull(redis.getExpire(key))
        assertTrue(ttl in (Duration.ofMinutes(14).seconds)..(Duration.ofMinutes(15).seconds), "ttl=$ttl")

        assertEquals(signedUp.ownerId, owners.logIn(OwnerCommand.LogIn(email, password)))
        assertNull(redis.opsForValue().get(key))

        repeat(5) { assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerCommand.LogIn(email, "wrong-password-value")) } }
        assertFailsWith<UnauthenticatedException> { owners.logIn(OwnerCommand.LogIn(email, password)) }
        assertEquals("5", redis.opsForValue().get(key))
    }
}
