package com.resilientpos.owner.internal.adapter.web

import com.resilientpos.owner.OwnerCommand

/** `POST /owner/v1/signup` 요청. 이메일·비밀번호 형식은 도메인이 검사한다. `toString`이 비밀번호를 가린다. */
data class SignUpRequest(val email: String, val password: String) {
    fun toCommand(): OwnerCommand.SignUp = OwnerCommand.SignUp(email, password)

    override fun toString(): String = "SignUpRequest(email=$email, password=***)"
}
