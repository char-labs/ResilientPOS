package com.resilientpos.terminal

import com.resilientpos.terminal.internal.adapter.persistence.query.TerminalQueries
import org.springframework.stereotype.Service

/** `terminal`의 조회 진입점. 상태를 바꾸는 진입점은 [TerminalManagement] 하나로 남기려고 따로 둔다. */
@Service
class TerminalQueryFacade(
    private val terminals: TerminalQueries,
) {

    /** 그 매장의 단말을 `terminalCode` 오름차순으로 돌려준다. 단말이 없으면 빈 목록이다. */
    fun findByStore(query: TerminalQuery.FindByStore): List<TerminalView> = terminals.findByStore(query.storeId)
}
