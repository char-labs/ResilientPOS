package com.resilientpos.terminal

import com.resilientpos.store.StoreId

/**
 * `terminal`의 쓰기 커맨드. use case마다 커맨드 하나이고, 그 결과는 커맨드 안에 둔다.
 */
object TerminalCommand {

    /** 매장에 새 단말을 `ACTIVE`로 등록하고 첫 credential을 발급한다. 매장 존재는 확인하지 않는다. */
    data class Register(val storeId: StoreId, val terminalCode: TerminalCode) {
        /** [token]은 평문이고 저장하지 않으므로 이 결과가 유일한 기회다. */
        data class Result(val terminalId: TerminalId, val terminalCode: TerminalCode, val token: String) {
            override fun toString(): String = "Result(terminalId=$terminalId, terminalCode=$terminalCode, token=***)"
        }
    }

    /** 유효 credential을 폐기하고 새로 발급한다. 옛 토큰은 커밋부터 인증되지 않는다. 단말이 [storeId]의 것이 아니면 `TERMINAL_NOT_FOUND`다. */
    data class RotateCredential(val storeId: StoreId, val terminalId: TerminalId) {
        /** [token]은 평문이고 저장하지 않으므로 이 결과가 유일한 기회다. */
        data class Result(val token: String) {
            override fun toString(): String = "Result(token=***)"
        }
    }

    /** 단말을 `INACTIVE`로 바꾼다. 이미 비활성이면 그대로다. 단말이 [storeId]의 것이 아니면 `TERMINAL_NOT_FOUND`다. */
    data class Deactivate(val storeId: StoreId, val terminalId: TerminalId) {
        /** 감사 로그와 멱등 응답을 위해 전후 상태를 함께 돌려준다. 이미 비활성이었다면 [before]도 `INACTIVE`다. */
        data class Result(val terminalId: TerminalId, val before: TerminalStatus, val after: TerminalStatus)
    }
}
