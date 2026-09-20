package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.CreateOrderResult
import com.resilientpos.order.OrderStatus
import java.util.UUID

/**
 * `POST /v1/orders`의 성공 응답 본문. 값 클래스 `OrderId`를 그대로 직렬화하지 않고 `value`를 꺼내서 담는다.
 */
data class CreateOrderResponse(
    val orderId: UUID,
    val status: OrderStatus,
) {
    companion object {
        fun from(result: CreateOrderResult) = CreateOrderResponse(result.orderId.value, result.status)
    }
}
