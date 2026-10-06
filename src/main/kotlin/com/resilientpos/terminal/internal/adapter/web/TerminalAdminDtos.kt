package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.terminal.TerminalStatus
import java.util.UUID

/** `POST /admin/v1/terminals` 요청. 코드 형식은 [com.resilientpos.terminal.TerminalCode]가 검사한다. */
data class RegisterTerminalRequest(val storeId: UUID, val terminalCode: String)

/** [token]은 평문이고 이 응답이 유일한 기회다. `toString`이 가린다. */
data class RegisterTerminalResponse(val terminalId: UUID, val terminalCode: String, val token: String) {
    override fun toString(): String = "RegisterTerminalResponse(terminalId=$terminalId, terminalCode=$terminalCode, token=***)"
}

/** [token]은 평문이고 이 응답이 유일한 기회다. `toString`이 가린다. */
data class RotateCredentialResponse(val token: String) {
    override fun toString(): String = "RotateCredentialResponse(token=***)"
}

data class DeactivateTerminalResponse(val terminalId: UUID, val status: TerminalStatus)

/** 목록 항목. 토큰은 싣지 않는다. */
data class TerminalResponse(val terminalId: UUID, val terminalCode: String, val status: TerminalStatus)
