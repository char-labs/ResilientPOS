package com.resilientpos.owner

/**
 * `owner`의 쓰기 커맨드. 비밀번호는 평문이므로 `toString`에서 가린다.
 */
object OwnerCommand {

    /** 이메일과 비밀번호로 계정을 만든다. 로그인시키지 않는다. */
    data class SignUp(val email: String, val password: String) {
        /** [email]은 정규화(앞뒤 공백 제거, 소문자)된 값이다. */
        data class Result(val ownerId: OwnerId, val email: String)

        override fun toString(): String = "SignUp(email=$email, password=***)"
    }

    /** 자격을 확인한다. 실패 원인은 호출자에게 구분해 알리지 않는다. */
    data class LogIn(val email: String, val password: String) {
        /** [email]은 정규화(앞뒤 공백 제거, 소문자)된 값이다. */
        data class Result(val ownerId: OwnerId, val email: String)

        override fun toString(): String = "LogIn(email=$email, password=***)"
    }
}
