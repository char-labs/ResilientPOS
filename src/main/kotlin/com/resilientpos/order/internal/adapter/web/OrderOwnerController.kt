package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderQueryFacade
import com.resilientpos.order.OrderStatus
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 점주용 주문 목록 endpoint. `/owner` 아래는 `OwnerSecurityFilterChain`이 세션 쿠키로 지킨다. principal은 점주 ID(UUID 문자열)다.
 * 먼저 `StoreManagement.requireOwned`로 매장 소유를 확인한다(남의 매장·없는 매장은 `404 STORE_NOT_FOUND`).
 */
@RestController
class OrderOwnerController(
    private val stores: StoreManagement,
    private val queryFacade: OrderQueryFacade,
) {

    /**
     * 정렬은 `occurred_at desc, id desc`로 고정이다. `size`와 cursor의 해석 실패는 Criteria의 `require`에 닿기 전에 `400 BAD_REQUEST`로 끊는다.
     * 쿼리 문자열의 offset `+`는 `%2B`로 보내야 한다. 그대로 보내면 공백으로 읽혀 시각 형식 오류(400)가 된다.
     */
    @GetMapping("/owner/v1/stores/{storeId}/orders")
    fun findOrders(
        @PathVariable storeId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: OffsetDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: OffsetDateTime?,
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) cursor: String?,
        @AuthenticationPrincipal ownerId: String,
    ): OrderPageResponse {
        stores.requireOwned(StoreId(storeId), OwnerId(UUID.fromString(ownerId)))
        val pageSize = size ?: OrderQuery.FindOrders.Criteria.DEFAULT_SIZE
        if (pageSize !in 1..OrderQuery.FindOrders.Criteria.MAX_SIZE) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val criteria = OrderQuery.FindOrders.Criteria(from?.toStorableInstant(), to?.toStorableInstant(), status, pageSize, cursor?.let(OrderCursorCodec::decode))
        return OrderPageResponse.from(queryFacade.findOrders(OrderQuery.FindOrders(StoreId(storeId), criteria)))
    }
}

private fun OffsetDateTime.toStorableInstant(): Instant = OrderCursorCodec.requireStorable(toInstant())
