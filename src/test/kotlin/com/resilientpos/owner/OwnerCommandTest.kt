package com.resilientpos.owner

import kotlin.test.Test
import kotlin.test.assertFalse

/** 평문 비밀번호는 로그·예외 메시지에 커맨드가 문자열로 들어가도 새지 않는다. */
class OwnerCommandTest {

    private val secret = "SECRET-PASSWORD-VALUE"

    @Test
    fun `SignUp의 toString에는 비밀번호가 없다`() {
        assertFalse(OwnerCommand.SignUp("owner@example.com", secret).toString().contains(secret))
    }

    @Test
    fun `LogIn의 toString에는 비밀번호가 없다`() {
        assertFalse(OwnerCommand.LogIn("owner@example.com", secret).toString().contains(secret))
    }
}
