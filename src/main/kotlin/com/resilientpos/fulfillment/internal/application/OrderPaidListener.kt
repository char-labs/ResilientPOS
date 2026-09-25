package com.resilientpos.fulfillment.internal.application

import com.resilientpos.contract.OrderPaid
import com.resilientpos.platform.logger
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * 결제된 주문의 사실을 받아 fulfillment를 기록한다.
 *
 * 옮기는 일만 하고 판단하지 않는다. 멱등 판정과 트랜잭션은 [FulfillmentCommandFacade]가 소유한다.
 *
 * 비동기이고 커밋 뒤에 돈다. 트랜잭션을 끄는 이유는 `PaymentApprovedListener`와 같다 —
 * 이 메서드가 트랜잭션이 아니어야 Facade가 자기 트랜잭션을 열고 닫아, 반환하는 순간이 곧 커밋 뒤가 된다.
 *
 * 여기서 실패하면 `event_publication`에 `FAILED`로 남고 자동 재시도가 없다. 예외를 삼키지 않는다 —
 * 삼키면 성공으로 기록되어 fulfillment 없는 `PAID` 주문의 흔적마저 사라진다.
 */
@Component
class OrderPaidListener(
    private val fulfillmentCommandFacade: FulfillmentCommandFacade,
) {

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: OrderPaid) {
        val result = fulfillmentCommandFacade.createFulfillment(FulfillmentCommand.Create(event.orderId))

        // Facade가 반환했으면 이미 커밋됐다. 경합에서 진 쪽은 그 커밋에서 unique 위반을 받아 여기 닿지 않는다.
        log.info { "fulfillment 기록 orderId=${event.orderId} fulfillmentId=${result.fulfillmentId.value} created=${result.created}" }
    }

    companion object {
        private val log by logger()
    }
}
