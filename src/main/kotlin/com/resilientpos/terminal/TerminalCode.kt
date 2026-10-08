package com.resilientpos.terminal

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException

/**
 * 단말 코드. 점주가 현장에서 단말을 부르는 이름이고 매장 안에서 유일하다(유일성은 DB unique가 지킨다).
 * 영대문자·숫자·하이픈 1~32자다. 소문자를 대문자로 바꾸지 않고 거절해 요청과 응답의 값이 같게 한다.
 */
@JvmInline
value class TerminalCode(val value: String) {
    init {
        if (!FORMAT.matches(value)) {
            throw DomainException(INVALID_TERMINAL_CODE, "단말 코드는 영대문자·숫자·하이픈 1~32자여야 합니다", DomainErrorKind.INVALID)
        }
    }

    companion object {
        const val INVALID_TERMINAL_CODE = "INVALID_TERMINAL_CODE"
        private val FORMAT = Regex("^[A-Z0-9-]{1,32}$")
    }
}
