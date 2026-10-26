package com.resilientpos.order

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.order.internal.adapter.persistence.query.OrderQueries
import com.resilientpos.order.internal.domain.Order
import org.springframework.stereotype.Service

/**
 * `order`의 조회 진입점. 상태를 바꾸는 진입점은 [OrderCommandFacade] 하나로 남기려고 따로 둔다.
 * 트랜잭션을 선언하지 않는다. 조회 하나를 위임할 뿐이다.
 */
@Service
class OrderQueryFacade(
    private val orders: OrderQueries,
) {

    /** 없는 주문과 다른 매장의 주문은 같은 `ORDER_NOT_FOUND`다. 다른 매장 주문의 존재를 드러내지 않는다. */
    fun getOrder(query: OrderQuery.GetOrder): OrderDetailView =
        orders.getOrder(query.orderId, query.storeId)
            ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)
}
