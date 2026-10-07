package com.resilientpos.owner.internal.adapter.web

import com.resilientpos.owner.OwnerCommand

/** `POST /owner/v1/login` 요청. `toString`이 비밀번호를 가린다. */
data class LogInRequest(val email: String, val password: String) {
    fun toCommand(): OwnerCommand.LogIn = OwnerCommand.LogIn(email, password)

    override fun toString(): String = "LogInRequest(email=$email, password=***)"
}
