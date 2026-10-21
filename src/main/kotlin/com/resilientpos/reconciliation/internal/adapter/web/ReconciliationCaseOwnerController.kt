package com.resilientpos.reconciliation.internal.adapter.web

import com.resilientpos.owner.OwnerId
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.ReconciliationQuery
import com.resilientpos.reconciliation.ReconciliationQueryFacade
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 점주용 대조 case endpoint. `/owner` 아래는 `OwnerSecurityFilterChain`이 세션 쿠키와 CSRF 토큰으로 지킨다. principal은 점주 ID(UUID 문자열)다.
 * 모든 endpoint는 먼저 `StoreManagement.requireOwned`로 매장 소유를 확인한다(남의 매장·없는 매장은 `404 STORE_NOT_FOUND`).
 */
@RestController
class ReconciliationCaseOwnerController(
    private val stores: StoreManagement,
    private val management: ReconciliationManagement,
    private val queryFacade: ReconciliationQueryFacade,
) {

    @GetMapping("/owner/v1/stores/{storeId}/reconciliation-cases")
    fun findReviewable(@PathVariable storeId: UUID, @AuthenticationPrincipal ownerId: String): List<ReconciliationCaseResponse> {
        stores.requireOwned(StoreId(storeId), OwnerId(UUID.fromString(ownerId)))
        return queryFacade.findReviewable(ReconciliationQuery.FindReviewable(StoreId(storeId))).map(ReconciliationCaseResponse::from)
    }

    @PostMapping("/owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve")
    fun resolve(
        @PathVariable storeId: UUID,
        @PathVariable caseId: UUID,
        @RequestBody request: ResolveReconciliationCaseRequest,
        @AuthenticationPrincipal ownerId: String,
    ): ResolveReconciliationCaseResponse {
        val owner = UUID.fromString(ownerId)
        stores.requireOwned(StoreId(storeId), OwnerId(owner))
        return ResolveReconciliationCaseResponse.from(management.resolve(request.toCommand(storeId, caseId, owner)))
    }
}
