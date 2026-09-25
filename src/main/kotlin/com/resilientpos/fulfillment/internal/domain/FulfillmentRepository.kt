package com.resilientpos.fulfillment.internal.domain

import java.util.UUID

/**
 * `Fulfillment`를 저장·조회하는 port. 시그니처에 aggregate와 원시 값만 쓴다.
 */
interface FulfillmentRepository {

    /** 그 주문의 fulfillment를 읽는다. 원본 주문당 한 번만 만든다는 멱등 판단의 근거다. */
    fun findByOrderId(orderId: UUID): Fulfillment?

    /**
     * 새 fulfillment를 넣는다. 트랜잭션 안에서만 부른다.
     * 같은 주문의 fulfillment가 이미 있으면 DB unique 제약이 거절하고 이 호출이 실패한다.
     */
    fun save(fulfillment: Fulfillment): Fulfillment
}
