package com.resilientpos.order.internal.application

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderId
import com.resilientpos.platform.logger
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 결제 승인을 받아 주문을 `PAID`로 전이한다. 확정값과 다르면 Facade가 `DomainException`을 던져 재시도 없이 DLT로 간다.
 * 이 메서드는 트랜잭션이 아니어서 Facade가 반환할 때 이미 커밋된 뒤라 로그가 참이다.
 */
@Component
class PaymentApprovedListener(
    private val orderCommandFacade: OrderCommandFacade,
) {

    @KafkaListener(topics = [PaymentApproved.TOPIC], groupId = "order.payment-approved")
    fun on(event: PaymentApproved) {
        // 취소 승인도 같은 사실로 온다. 결제 완료로 읽으면 안 된다.
        if (event.kind != PaymentAttemptKind.AUTHORIZATION) return

        val result = orderCommandFacade.markPaid(OrderCommand.MarkPaid(OrderId(event.orderId), event.checkoutNo, event.amount))
        log.info { "주문 결제 반영 orderId=${event.orderId} checkoutNo=${event.checkoutNo} status=${result.status}" }
    }

    companion object {
        private val log by logger()
    }
}
