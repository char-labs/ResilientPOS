package com.resilientpos.terminal

import com.resilientpos.store.StoreId

/** `terminal`의 읽기 조회. use case마다 조회 하나이고, 투영은 최상위 [TerminalView]다. */
object TerminalQuery {

    /** 한 매장의 단말을 모두 읽는다. 매장 하나의 단말 수는 한정적이라 페이지네이션이 없다. */
    data class FindByStore(val storeId: StoreId)
}
