package com.resilientpos.owner.internal.adapter.web

import com.resilientpos.owner.OwnerCommand
import java.util.UUID

/** 가입과 로그인의 응답. [email]은 정규화(앞뒤 공백 제거, 소문자)된 값이다. */
data class OwnerResponse(val ownerId: UUID, val email: String) {
    companion object {
        fun from(result: OwnerCommand.SignUp.Result) = OwnerResponse(result.ownerId.value, result.email)

        fun from(result: OwnerCommand.LogIn.Result) = OwnerResponse(result.ownerId.value, result.email)
    }
}
