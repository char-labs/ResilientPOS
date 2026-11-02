package com.resilientpos.reconciliation.internal.adapter.web

import com.resilientpos.reconciliation.ReconciliationQuery

/** `GET /owner/v1/stores/{storeId}/reconciliation-cases`의 성공 응답 본문. `truncated`가 true면 미해소 case가 상한을 넘어 가장 오래된 것이 `items`에 없다. */
data class ReconciliationCasePageResponse(
    val items: List<ReconciliationCaseResponse>,
    val truncated: Boolean,
) {
    companion object {
        fun from(result: ReconciliationQuery.FindReviewable.Result) = ReconciliationCasePageResponse(
            items = result.items.map(ReconciliationCaseResponse::from),
            truncated = result.truncated,
        )
    }
}
