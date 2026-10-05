package com.resilientpos.terminal

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse

/** 평문 토큰은 발급 결과에만 있다. 로그·예외 메시지에 결과 객체가 문자열로 들어가도 토큰이 새지 않는다. */
class TerminalCommandTest {

    private val secret = "SECRET-TOKEN-VALUE"

    @Test
    fun `Register 결과의 toString에는 토큰이 없다`() {
        val text = TerminalCommand.Register.Result(TerminalId(UUID.randomUUID()), secret).toString()

        assertFalse(text.contains(secret), text)
    }

    @Test
    fun `RotateCredential 결과의 toString에는 토큰이 없다`() {
        val text = TerminalCommand.RotateCredential.Result(secret).toString()

        assertFalse(text.contains(secret), text)
    }
}
