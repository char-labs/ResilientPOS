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

    /** 이메일·비밀번호를 검증하고 계정을 만든다. 사전 조회 없이 넣고, 같은 이메일이면 `OWNER_EMAIL_DUPLICATED`다. */
    fun signUp(command: OwnerCommand.SignUp): OwnerCommand.SignUp.Result {
        val email = OwnerEmail.of(command.email)
        OwnerPassword.requireValid(command.password)
        val owner = Owner.register(email, checkNotNull(passwordEncoder.encode(command.password)), identifierGenerator)
        Tx.masterTx { repository.save(owner) }
        return OwnerCommand.SignUp.Result(owner.id, email.value)
    }

    /**
     * 자격을 확인한다. 없는 계정·틀린 비밀번호·잠김은 모두 같은 [UnauthenticatedException]이다. 쿠키 세션 로그인이라 `WWW-Authenticate`를 싣지 않는다.
     * 실패가 [MAX_FAILURES]번 쌓인 이메일은 비밀번호를 비교하지 않고 거절한다. 잠금은 횟수의 창이 끝나면 풀린다.
     */
    fun logIn(command: OwnerCommand.LogIn): OwnerCommand.LogIn.Result {
        val email = try {
            OwnerEmail.of(command.email)
        } catch (e: DomainException) {
            throw UnauthenticatedException()
        }
        if (failures.count(email) >= MAX_FAILURES) throw UnauthenticatedException()

        val owner = Tx.readOnlyTx { repository.findByEmail(email) }
        // 정책을 못 맞춘 입력은 저장된 어떤 비밀번호와도 같을 수 없고, bcrypt가 72바이트 초과를 거절하므로 비교하지 않는다.
        val matched = OwnerPassword.isValid(command.password) &&
            passwordEncoder.matches(command.password, owner?.passwordHash ?: dummyHash)
        if (owner == null || !matched) {
            failures.increment(email)
            throw UnauthenticatedException()
        }
        failures.clear(email)
        return OwnerCommand.LogIn.Result(owner.id, email.value)
    }

    private companion object {
        const val MAX_FAILURES = 5
        const val DUMMY_PASSWORD = "dummy-password-for-timing-only"
    }
}
