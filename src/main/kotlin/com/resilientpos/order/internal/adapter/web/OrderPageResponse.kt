package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderQuery

/** `GET /owner/v1/stores/{storeId}/orders`의 성공 응답 본문. `nextCursor`는 다음 페이지가 없으면 null이고 클라이언트에게 불투명하다. */
data class OrderPageResponse(
    val items: List<OrderSummaryResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(result: OrderQuery.FindOrders.Result) = OrderPageResponse(
            items = result.items.map(OrderSummaryResponse::from),
            nextCursor = result.next?.let(OrderCursorCodec::encode),
        )
    }
}
