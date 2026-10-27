package com.resilientpos.order

import com.resilientpos.store.StoreId
import java.time.Instant

/**
 * `order`의 읽기 조회. use case마다 조회 하나이고, 투영은 이름공간 밖 최상위 `*View`다([OrderDetailView]).
 */
object OrderQuery {

    /** 주문 하나를 매장 기준으로 읽는다. 다른 매장의 주문은 없는 주문과 같게 취급한다. */
    data class GetOrder(
        val orderId: OrderId,
        val storeId: StoreId,
    )

    /**
     * 매장의 주문을 발생 시각 최신순(`occurred_at desc, id desc`)으로 한 페이지 읽는다. 정렬은 고정이다.
     * 다음 페이지는 offset이 아니라 마지막 항목의 [Cursor]로 읽는다.
     */
    data class FindOrders(
        val storeId: StoreId,
        val criteria: Criteria,
    ) {
        /** 기간은 `[from, to)`다. `size`의 범위(1~50) 검증은 요청을 받는 Controller의 몫이라 여기서는 계약만 둔다. */
        data class Criteria(
            val from: Instant? = null,
            val to: Instant? = null,
            val status: OrderStatus? = null,
            val size: Int = DEFAULT_SIZE,
            val after: Cursor? = null,
        ) {
            init {
                require(size in 1..MAX_SIZE) { "size는 1~${MAX_SIZE}여야 합니다" }
            }

            companion object {
                const val DEFAULT_SIZE = 20
                const val MAX_SIZE = 50
            }
        }

        /** 마지막으로 읽은 항목의 위치. 이 위치 다음(더 오래되거나 같은 시각에서 id가 작은) 항목부터 읽는다. */
        data class Cursor(
            val occurredAt: Instant,
            val orderId: OrderId,
        )

        /** [next]는 다음 페이지가 없으면 null이다. */
        data class Result(
            val items: List<OrderSummaryView>,
            val next: Cursor?,
        )
    }
}
