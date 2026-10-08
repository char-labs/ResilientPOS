package com.resilientpos.owner.internal.application

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.owner.OwnerCommand
import com.resilientpos.owner.OwnerManagement
import com.resilientpos.owner.internal.adapter.redis.RedisLoginFailureCounter
import com.resilientpos.owner.internal.domain.LoginFailureCounter
import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.owner.internal.domain.OwnerRepository
import com.resilientpos.platform.SessionStoreUnavailableException
import com.resilientpos.platform.Tx
import com.resilientpos.platform.TxRunner
import com.resilientpos.platform.UnauthenticatedException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

/** 실제 Facade와 도메인에 저장소·횟수 port만 메모리 fake로 바꾼 단위 테스트. 제한 경계는 Facade가 정한다. */
class OwnerManagementTest {

    private val password = "correct-horse-battery"
    private val email = "owner@example.com"

    private val owners = InMemoryOwners()
    private val counter = InMemoryCounter()
    private val management = OwnerManagement(owners, counter, UuidV7IdentifierGenerator())

    @BeforeTest
    fun initTx() = Tx.initialize(TxRunner())

    private fun signUp() = management.signUp(OwnerCommand.SignUp(email, password))

    private fun logInFails(pw: String = "wrong-password-value", mail: String = email) {
        val e = assertFailsWith<UnauthenticatedException> { management.logIn(OwnerCommand.LogIn(mail, pw)) }
        assertEquals(null, e.challenge)
    }

    @Test
    fun `가입하면 정규화한 이메일과 새 id를 돌려주고 {bcrypt} 해시를 저장한다`() {
        val result = management.signUp(OwnerCommand.SignUp("  Owner@Example.COM ", password))

        assertEquals(email, result.email)
        val saved = owners.byEmail.getValue(email)
        assertEquals(result.ownerId, saved.id)
        assertTrue(saved.passwordHash.startsWith("{bcrypt}"), saved.passwordHash)
        assertTrue(!saved.passwordHash.contains(password))
    }

    @Test
    fun `이메일이 틀리면 INVALID_EMAIL, 비밀번호가 틀리면 INVALID_PASSWORD로 가입을 거절한다`() {
        assertEquals(Owner.INVALID_EMAIL, assertFailsWith<DomainException> { management.signUp(OwnerCommand.SignUp("nope", password)) }.code)
        assertEquals(Owner.INVALID_PASSWORD, assertFailsWith<DomainException> { management.signUp(OwnerCommand.SignUp(email, "short")) }.code)
        assertTrue(owners.byEmail.isEmpty())
    }

    @Test
    fun `맞는 비밀번호로 로그인하면 가입한 점주의 id와 정규화한 이메일을 돌려준다`() {
        val signedUp = signUp()

        val result = management.logIn(OwnerCommand.LogIn("  OWNER@example.com ", password))

        assertEquals(signedUp.ownerId, result.ownerId)
        assertEquals(email, result.email)
    }

    @Test
    fun `없는 계정과 틀린 비밀번호는 같은 예외이고 둘 다 실패를 센다`() {
        signUp()

        logInFails()
        logInFails(mail = "nobody@example.com")

        assertEquals(1, counter.counts[email])
        assertEquals(1, counter.counts["nobody@example.com"])
    }

    @Test
    fun `4번 실패한 뒤에는 맞는 비밀번호로 로그인된다`() {
        signUp()
        repeat(4) { logInFails() }

        management.logIn(OwnerCommand.LogIn(email, password))
    }

    @Test
    fun `6번째 시도는 맞는 비밀번호도 거절하고 비밀번호를 비교하지 않는다`() {
        signUp()
        repeat(5) { logInFails() }
        val lookups = owners.lookups

        logInFails(pw = password)

        assertEquals(lookups, owners.lookups, "한도를 넘긴 시도는 조회도 비교도 하지 않는다")
        assertEquals(6, counter.counts[email], "시도는 비교 전에 센다")
    }

    @Test
    fun `시도를 비교보다 먼저 센다`() {
        signUp()
        counter.counts[email] = 5

        logInFails(pw = password)
        assertEquals(0, owners.lookups)
        assertEquals(6, counter.counts[email])
    }

    @Test
    fun `성공하면 횟수를 지운다`() {
        signUp()
        repeat(4) { logInFails() }

        management.logIn(OwnerCommand.LogIn(email, password))

        assertEquals(null, counter.counts[email])
        repeat(4) { logInFails() }
        management.logIn(OwnerCommand.LogIn(email, password))
    }

    @Test
    fun `Redis 오류면 비밀번호를 비교하지 않고 503 예외를 던진다`() {
        listOf<RuntimeException>(
            IllegalStateException("LettuceConnectionFactory was destroyed"),
            RedisConnectionFailureException("Unable to connect to Redis"),
        ).forEach(::failsClosedWhenRedisThrows)
    }

    private fun failsClosedWhenRedisThrows(error: RuntimeException) {
        val closed = mock(StringRedisTemplate::class.java) { throw error }
        val owners = InMemoryOwners()
        val failClosed = OwnerManagement(owners, RedisLoginFailureCounter(closed), UuidV7IdentifierGenerator())
        failClosed.signUp(OwnerCommand.SignUp(email, password))
        val lookupsBefore = owners.lookups

        listOf(password, "wrong-password-value").forEach { pw ->
            val thrown = assertFailsWith<SessionStoreUnavailableException> { failClosed.logIn(OwnerCommand.LogIn(email, pw)) }
            assertSame(error, thrown.cause)
        }
        assertEquals(lookupsBefore, owners.lookups)
    }

    @Test
    fun `로그인 성공 뒤 횟수를 지우지 못해도 로그인은 성공한다`() {
        val template = mock(StringRedisTemplate::class.java) { inv ->
            when (inv.method.name) {
                "execute" -> 1L
                else -> throw RedisConnectionFailureException("Unable to connect to Redis")
            }
        }
        val failingClear = OwnerManagement(InMemoryOwners(), RedisLoginFailureCounter(template), UuidV7IdentifierGenerator())
        val signedUp = failingClear.signUp(OwnerCommand.SignUp(email, password))

        assertEquals(signedUp.ownerId, failingClear.logIn(OwnerCommand.LogIn(email, password)).ownerId)
    }

    @Test
    fun `정책을 못 맞춘 비밀번호는 로그인에서도 예외 없이 인증 실패다`() {
        signUp()

        logInFails(pw = "x".repeat(100))
        assertNotEquals(0, counter.counts[email])
    }

    private class InMemoryOwners : OwnerRepository {
        val byEmail = mutableMapOf<String, Owner>()
        var lookups = 0

        override fun save(owner: Owner): Owner {
            if (byEmail.putIfAbsent(owner.email.value, owner) != null) throw Owner.emailDuplicated()
            return owner
        }

        override fun findByEmail(email: OwnerEmail): Owner? = byEmail[email.value].also { lookups++ }
    }

    private class InMemoryCounter : LoginFailureCounter {
        val counts = mutableMapOf<String, Long>()
        override fun increment(email: OwnerEmail): Long = checkNotNull(counts.merge(email.value, 1, Long::plus))
        override fun clear(email: OwnerEmail) { counts.remove(email.value) }
    }
}
