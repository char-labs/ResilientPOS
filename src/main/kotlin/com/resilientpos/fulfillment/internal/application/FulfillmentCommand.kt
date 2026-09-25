package com.resilientpos.fulfillment.internal.application

import com.resilientpos.fulfillment.internal.domain.FulfillmentId
import java.util.UUID

/**
 * `fulfillment`의 쓰기 커맨드. use case마다 커맨드 하나이고, 그 결과는 커맨드 안에 둔다.
 * 모듈 밖에서 부르는 쪽이 없어 [FulfillmentCommandFacade]와 함께 `internal`에 둔다(도메인 모델 §2).
 */
object FulfillmentCommand {

    /**
     * 결제된 주문의 fulfillment를 만든다. `order`가 낸 `OrderPaid`를 옮긴 것이고,
     * `fulfillment`는 `order`의 테이블을 읽지 않는다.
     */
    data class Create(
        val orderId: UUID,
    ) {
        /**
         * 생성 결과. 그 주문의 fulfillment가 이미 있었으면 새로 만들지 않고 그 fulfillment를 돌려주며 [created]가 `false`다.
         */
        data class Result(
            val fulfillmentId: FulfillmentId,
            val created: Boolean,
        )
    }
}
