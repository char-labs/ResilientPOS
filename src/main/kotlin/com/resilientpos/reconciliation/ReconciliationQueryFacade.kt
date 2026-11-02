package com.resilientpos.reconciliation

import com.resilientpos.reconciliation.internal.adapter.persistence.query.ReconciliationCaseQueries
import org.springframework.stereotype.Service

/**
 * `reconciliation`의 조회 진입점. case 정본 상태를 바꾸는 진입점은 [ReconciliationManagement] 하나로 남기려고 따로 둔다.
 * 트랜잭션을 선언하지 않는다. 조회 하나를 위임할 뿐이다.
 */
@Service
class ReconciliationQueryFacade(
    private val cases: ReconciliationCaseQueries,
) {

    /**
     * 그 매장의 `MANUAL_REVIEW` case를 최신순(`id` 내림차순)으로 최대 [ReconciliationCaseQueries.REVIEWABLE_LIMIT]건 돌려준다.
     * 상한보다 한 건 더 읽어 넘었으면 `truncated`를 true로 하고 가장 오래된 한 건을 뺀다. 빠진 case를 읽을 방법은 없다.
     * 미해소 건은 점주가 처리하면 줄어드는 목록이라 페이지를 두지 않는다. 잘림이 실제로 보이면 커서를 더한다.
     */
    fun findReviewable(query: ReconciliationQuery.FindReviewable): ReconciliationQuery.FindReviewable.Result {
        val read = cases.findReviewable(query.storeId)
        return ReconciliationQuery.FindReviewable.Result(read.take(ReconciliationCaseQueries.REVIEWABLE_LIMIT), truncated = read.size > ReconciliationCaseQueries.REVIEWABLE_LIMIT)
    }
}
