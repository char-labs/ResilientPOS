package com.resilientpos.payment.internal.domain

import java.util.UUID

/**
 * checkout 회차 하나의 결제 시도 생성을 여러 인스턴스 사이에서 줄 세우는 port.
 *
 * 최선 노력이다. 정합성은 DB의 `ux_payment_attempt_first_authorization`이 지키고, 이 잠금은 늦게 온 같은 사실이
 * unique 위반으로 실패하는 대신 앞 시도를 돌려받게 하는 완화 수단이다. 그래서 잠금을 얻지 못해도 던지지 않는다.
 */
interface CheckoutLock {

    /**
     * 그 checkout을 지금 트랜잭션이 끝날 때까지 잠근다. 다른 인스턴스가 쥐고 있으면 풀릴 때까지 기다린다.
     * 커밋 뒤에 풀려야 뒤 요청이 앞 시도를 보므로 트랜잭션 안에서만 부른다.
     * 기다리는 한도를 넘기거나 잠금 저장소가 응답하지 않으면 잠그지 않고 돌아온다.
     */
    fun lockUntilTransactionEnds(orderId: UUID, checkoutNo: Int)
}
