package com.resilientpos.fulfillment.internal.application

import com.resilientpos.contract.OrderPaid
import com.resilientpos.platform.logger
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 결제된 주문의 사실을 받아 fulfillment를 기록한다.
 *
 * 옮기는 일만 하고 판단하지 않는다. 멱등 판정과 트랜잭션은 [FulfillmentCommandFacade]가 소유한다.
 *
 * Kafka consumer라 발행한 쪽의 커밋 뒤에 돈다. 이 메서드는 트랜잭션이 아니어서 Facade가 자기 트랜잭션을 열고 닫아,
 * 반환하는 순간이 곧 커밋 뒤가 된다.
 *
 * 여기서 던지면 다시 시도되고 끝내 실패하면 DLT로 간다. 예외를 삼키지 않는다 —
 * 삼키면 offset이 넘어가 fulfillment 없는 `PAID` 주문의 흔적마저 사라진다.
 */
@Component
class OrderPaidListener(
    private val fulfillmentCommandFacade: FulfillmentCommandFacade,
) {

    @KafkaListener(topics = [OrderPaid.TOPIC], groupId = "fulfillment.order-paid")
    fun on(event: OrderPaid) {
        val result = fulfillmentCommandFacade.createFulfillment(FulfillmentCommand.Create(event.orderId))

        // Facade가 반환했으면 이미 커밋됐다. 경합에서 진 쪽은 그 커밋에서 unique 위반을 받아 여기 닿지 않는다.
        log.info { "fulfillment 기록 orderId=${event.orderId} fulfillmentId=${result.fulfillmentId.value} created=${result.created}" }
    }

    companion object {
        private val log by logger()
    }
}
