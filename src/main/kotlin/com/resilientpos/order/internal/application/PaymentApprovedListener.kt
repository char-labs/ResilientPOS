package com.resilientpos.order.internal.application

import com.resilientpos.contract.PaymentApproved
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderId
import com.resilientpos.platform.logger
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * 결제 승인을 받아 주문을 `PAID`로 전이한다. 확정값과 다르면 Facade가 던져 발행 기록이 `FAILED`로 남는다.
 * 기본값(`REQUIRES_NEW`)이면 Facade가 리스너의 트랜잭션에 합류해 로그가 커밋 전에 찍힌다.
 * 트랜잭션을 꺼 두면 Facade가 반환할 때 이미 커밋된 뒤라 로그가 참이다.
 */
@Component
class PaymentApprovedListener(
    private val orderCommandFacade: OrderCommandFacade,
) {

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
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
