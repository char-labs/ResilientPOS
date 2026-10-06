package com.resilientpos.payment

import com.resilientpos.payment.internal.adapter.persistence.query.PaymentAttemptQueries
import org.springframework.stereotype.Service

/**
 * `payment`의 조회 진입점. 결제 정본 상태를 바꾸는 진입점은 [PaymentManagement] 하나로 남기려고 따로 둔다.
 * 트랜잭션을 선언하지 않는다. 조회 하나를 위임할 뿐이고, 한 시점이 필요한 조회가 여럿 생기면 그 메서드가 `Tx.readOnlyTx`로 묶는다.
 */
@Service
class PaymentQueryFacade(
    private val attempts: PaymentAttemptQueries,
) {

    /**
     * 그 주문에서 매장이 같은 시도를 생성 순(`id` 오름차순)으로 돌려준다.
     * 주문이 없거나 다른 매장의 주문이거나 시도가 아직 없으면 모두 빈 목록이다. `payment`는 `order`의 테이블을 읽지 않아 셋을 구분하지 못한다.
     */
    fun findAttempts(query: PaymentQuery.FindAttempts): List<PaymentAttemptView> =
        attempts.findAttempts(query.orderId, query.storeId)
}
