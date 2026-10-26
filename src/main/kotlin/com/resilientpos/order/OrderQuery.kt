package com.resilientpos.order

import com.resilientpos.store.StoreId

/**
 * `order`의 읽기 조회. use case마다 조회 하나이고, 투영은 이름공간 밖 최상위 `*View`다([OrderDetailView]).
 */
object OrderQuery {

    /** 주문 하나를 매장 기준으로 읽는다. 다른 매장의 주문은 없는 주문과 같게 취급한다. */
    data class GetOrder(
        val orderId: OrderId,
        val storeId: StoreId,
    )
}
