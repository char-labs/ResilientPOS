package com.resilientpos.reconciliation

import com.resilientpos.store.StoreId

/** `reconciliation`의 읽기 조회. use case마다 조회 하나이고, 투영은 최상위 [ReconciliationCaseView]다. */
object ReconciliationQuery {

    /** 한 매장에서 점주가 아직 해소하지 않은(`MANUAL_REVIEW`) case를 최신순으로 읽는다. 상한을 넘으면 잘렸다고 알리고 페이지를 두지 않는 이유는 [ReconciliationQueryFacade.findReviewable]에 있다. */
    data class FindReviewable(val storeId: StoreId) {
        /** [items]는 최신순 최대 상한 건이고, 미해소 case가 상한을 넘어 오래된 것이 빠졌으면 [truncated]가 true다. */
        data class Result(val items: List<ReconciliationCaseView>, val truncated: Boolean)
    }
}
