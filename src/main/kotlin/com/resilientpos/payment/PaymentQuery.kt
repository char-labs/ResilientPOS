package com.resilientpos.payment

import com.resilientpos.store.StoreId
import java.util.UUID

/**
 * `payment`의 읽기 조회. use case마다 조회 하나이고, 투영은 이름공간 밖 최상위 `*View`다([PaymentAttemptView]).
 * 동적 검색 조건이 생기면 그 조회 안에 `Criteria`를 둔다. 지금은 없다.
 */
object PaymentQuery {

    /**
     * 한 주문의 결제 시도를 매장 기준으로 모두 읽는다. 회차와 종류를 가리지 않는다.
     * `orderId`가 raw `UUID`인 이유는 [PaymentCommand.CreateAttempt]와 같다. `payment`는 `order`의 타입에 의존하지 않는다.
     */
    data class FindAttempts(
        val orderId: UUID,
        val storeId: StoreId,
    )
}
