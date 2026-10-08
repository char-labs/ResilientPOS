package com.resilientpos.owner.internal.adapter.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerRequestsTest {

    @Test
    fun `가입과 로그인 요청의 toString은 비밀번호를 가린다`() {
        val signUp = SignUpRequest("owner@example.com", "super-secret-password").toString()
        val logIn = LogInRequest("owner@example.com", "super-secret-password").toString()

        assertFalse(signUp.contains("super-secret-password") || logIn.contains("super-secret-password"))
        assertTrue("password=***" in signUp && "password=***" in logIn)
    }
}
