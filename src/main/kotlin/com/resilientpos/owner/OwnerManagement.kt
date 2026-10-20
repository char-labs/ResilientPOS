package com.resilientpos.owner

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.owner.internal.domain.LoginFailureCounter
import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.owner.internal.domain.OwnerPassword
import com.resilientpos.owner.internal.domain.OwnerRepository
import com.resilientpos.platform.Tx
import com.resilientpos.platform.UnauthenticatedException
import com.resilientpos.platform.isUniqueViolationOf
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.stereotype.Service

/**
 * `owner` 모듈의 유일한 진입점. 점주 가입과 로그인 자격 확인은 여기를 거친다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 구현이 둘이 될 때 뽑는다.
 *
 * 해시는 bcrypt처럼 느려서 트랜잭션 밖에서 한다. 세션 발급은 이 모듈의 일이 아니다.
 */
@Service
class OwnerManagement(
    private val repository: OwnerRepository,
    private val failures: LoginFailureCounter,
    private val identifierGenerator: IdentifierGenerator,
) {
    private val passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /** 없는 계정도 이 해시와 비교해 응답 시간을 맞춘다. */
    private val dummyHash: String = checkNotNull(passwordEncoder.encode(DUMMY_PASSWORD))

    /**
     * 이메일·비밀번호를 검증하고 계정을 만든다. 사전 조회 없이 넣고, 같은 이메일이면 커밋에서 드러난 unique 위반을 여기서 `OWNER_EMAIL_DUPLICATED`로 번역한다.
     * 커밋이 이 블록에서 일어나야 번역할 수 있으므로 바깥 트랜잭션 안에서 부르지 않는다.
     */
    fun signUp(command: OwnerCommand.SignUp): OwnerCommand.SignUp.Result {
        val email = OwnerEmail.of(command.email)
        OwnerPassword.requireValid(command.password)
        val owner = Owner.register(email, checkNotNull(passwordEncoder.encode(command.password)), identifierGenerator)
        try {
            Tx.masterTx { repository.save(owner) }
        } catch (e: DataIntegrityViolationException) {
            if (e.isUniqueViolationOf(OWNER_EMAIL_CONSTRAINT)) throw Owner.emailDuplicated()
            throw e
        }
        return OwnerCommand.SignUp.Result(owner.id, email.value)
    }

    /**
     * 자격을 확인한다. 없는 계정·틀린 비밀번호·잠김은 모두 같은 [UnauthenticatedException]이다. 쿠키 세션 로그인이라 `WWW-Authenticate`를 싣지 않는다.
     * 시도를 비교보다 먼저 센다(정책 위반 비밀번호도 센다). 창 안의 [MAX_FAILURES]번을 넘긴 시도는 비밀번호를 비교하지 않고 거절한다.
     * 동시 요청은 서로 다른 횟수를 받으므로 비교까지 가는 요청이 한도를 넘지 않는다. 성공하면 횟수를 지우고, 잠금은 창이 끝나면 풀린다.
     */
    fun logIn(command: OwnerCommand.LogIn): OwnerCommand.LogIn.Result {
        val email = try {
            OwnerEmail.of(command.email)
        } catch (e: DomainException) {
            throw UnauthenticatedException()
        }
        if (failures.increment(email) > MAX_FAILURES) throw UnauthenticatedException()

        val owner = Tx.readOnlyTx { repository.findByEmail(email) }
        // 정책을 못 맞춘 입력은 저장된 어떤 비밀번호와도 같을 수 없고, bcrypt가 72바이트 초과를 거절하므로 비교하지 않는다.
        val matched = OwnerPassword.isValid(command.password) &&
            passwordEncoder.matches(command.password, owner?.passwordHash ?: dummyHash)
        if (owner == null || !matched) throw UnauthenticatedException()
        failures.clear(email)
        return OwnerCommand.LogIn.Result(owner.id, email.value)
    }

    private companion object {
        const val OWNER_EMAIL_CONSTRAINT = "ux_owner_email"
        const val MAX_FAILURES = 5
        const val DUMMY_PASSWORD = "dummy-password-for-timing-only"
    }
}
