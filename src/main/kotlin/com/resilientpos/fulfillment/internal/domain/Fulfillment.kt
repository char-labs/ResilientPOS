package com.resilientpos.fulfillment.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import java.util.UUID

/**
 * fulfillment aggregate 루트. 결제된 주문 하나를 이행하는 단위이고 주문 하나에 하나만 있다.
 * 업종(카페의 바, 식당의 주방, 소매 픽업)을 가리지 않는다. 원문 청사진의 `KitchenTicket`이 이것이고,
 * 이름은 Square Orders API의 `Fulfillment`를 따랐다. ticket은 KDS·프린터의 표시 매체라 쓰지 않는다.
 * `id`와 `orderId`만 가진다 — 상태·매장·품목 snapshot은 그것을 읽는 명령이나 조회가 없어서 두지 않는다.
 *
 * `create`가 `Transition`이 아니라 aggregate를 그대로 돌려준다. `FulfillmentCreated`를 내지 않기
 * 때문이다(도메인 모델 §4.3) — 받는 쪽이 없으면 기록도 전달도 되지 않는다.
 */
class Fulfillment private constructor(
    val id: FulfillmentId,
    val orderId: UUID,
) {

    override fun equals(other: Any?) = other is Fulfillment && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {

        /**
         * 이미 저장된 fulfillment를 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다.
         */
        fun restore(id: FulfillmentId, orderId: UUID): Fulfillment = Fulfillment(id, orderId)

        /**
         * 결제된 주문의 fulfillment를 만든다. 그 주문의 fulfillment가 이미 있는지 판단하는 일은 `FulfillmentCommandFacade`가 한다.
         */
        fun create(orderId: UUID, ids: IdentifierGenerator): Fulfillment =
            Fulfillment(FulfillmentId(ids.next()), orderId)
    }
}
