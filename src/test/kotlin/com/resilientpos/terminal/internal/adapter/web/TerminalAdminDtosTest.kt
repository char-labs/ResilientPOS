package com.resilientpos.terminal.internal.adapter.web

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse

class TerminalAdminDtosTest {

    @Test
    fun `토큰이 있는 응답 DTO의 toString은 평문 토큰을 가린다`() {
        val token = "dGVzdC10ZXJtaW5hbC10b2tlbi1maXh0dXJlLTAwMDA"

        assertFalse(RegisterTerminalResponse(UUID.randomUUID(), "POS-1", token).toString().contains(token))
        assertFalse(RotateCredentialResponse(token).toString().contains(token))
    }
}
