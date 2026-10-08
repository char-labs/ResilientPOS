package com.resilientpos.terminal.internal.adapter.persistence.query

import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalView
import com.resilientpos.terminal.internal.adapter.persistence.command.TerminalEntity
import org.springframework.stereotype.Component

/**
 * 단말 조회 구현. aggregate를 거치지 않고 엔티티를 바로 [TerminalView]로 바꾼다.
 * 구현이 하나뿐이고 View는 aggregate 경계에 들어가지 않아서 port 인터페이스를 두지 않는다.
 */
@Component
class TerminalQueries(
    private val queries: JdslQueryExecutor,
) {

    /** `ux_terminal_store_code (store_id, terminal_code)`를 탄다. 매장 하나의 단말 수는 한정적이라 페이지네이션이 없다. */
    @ReadOnlyTransactional
    fun findByStore(storeId: StoreId): List<TerminalView> =
        queries.findAll {
            selectFrom(TerminalEntity::class)
                .where(path(TerminalEntity::storeId).eq(storeId.value))
                .orderBy(path(TerminalEntity::terminalCode).asc())
        }.map { it.toView() }
}

private fun TerminalEntity.toView() = TerminalView(TerminalId(id), TerminalCode(terminalCode), status)
