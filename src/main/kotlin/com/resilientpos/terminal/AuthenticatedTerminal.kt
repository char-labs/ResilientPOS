package com.resilientpos.terminal

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.store.StoreId

/** 토큰 인증을 통과한 단말. 소유권 검사가 이 값과 요청의 식별자를 대조한다. */
data class AuthenticatedTerminal(val terminalId: TerminalId, val storeId: StoreId) {

    /** 요청이 실어 온 식별자가 이 단말의 것이 아니면 거절한다. `storeId`는 요청에 있을 때만 대조한다. */
    fun requireSame(terminalId: TerminalId, storeId: StoreId? = null) {
        if (terminalId != this.terminalId || (storeId != null && storeId != this.storeId)) {
            throw DomainException(TERMINAL_MISMATCH, "요청의 단말 또는 매장이 인증된 단말과 다릅니다", DomainErrorKind.FORBIDDEN)
        }
    }

    companion object {
        const val TERMINAL_MISMATCH = "TERMINAL_MISMATCH"
    }
}
