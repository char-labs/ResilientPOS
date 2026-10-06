package com.resilientpos.terminal

/** 단말의 읽기 투영. 토큰·credential은 싣지 않는다. */
data class TerminalView(
    val terminalId: TerminalId,
    val terminalCode: TerminalCode,
    val status: TerminalStatus,
)
