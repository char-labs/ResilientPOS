package com.resilientpos.payment.internal.application

import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.platform.logger
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `order`가 결제를 요청한 사실을 받아 시도를 기록한다. 이 저장소의 첫 프로덕션 리스너다.
 *
 * 옮기는 일만 하고 판단하지 않는다. 멱등 판정과 트랜잭션은 [PaymentManagement]가 소유한다.
 *
 * Kafka consumer라 발행한 쪽의 커밋 뒤에 돈다. 이 메서드는 트랜잭션이 아니어서 Facade가 자기 트랜잭션을 열고 닫아,
 * 반환하는 순간이 곧 커밋 뒤가 된다. 같은 주문의 메시지는 key(`orderId`)가 같아 한 파티션에서 차례로 온다.
 *
 * 여기서 던지면 `KafkaConfiguration`의 오류 처리기가 다시 시도하고 끝내 실패하면 DLT로 보낸다.
 * 그래서 예외를 삼키지 않는다 — 삼키면 offset이 넘어가 흔적 없이 사라진다.
 */
@Component
class OrderPaymentRequestedListener(
    private val paymentManagement: PaymentManagement,
) {

    @KafkaListener(topics = [OrderPaymentRequested.TOPIC], groupId = "payment.order-payment-requested")
    fun on(event: OrderPaymentRequested) {
        val result = paymentManagement.createAttempt(
            PaymentCommand.CreateAttempt(
                orderId = event.orderId,
                storeId = event.storeId,
                terminalId = event.terminalId,
                checkoutNo = event.checkoutNo,
                amount = event.amount,
            ),
        )

        // Facade가 반환했으면 이미 커밋됐다. 경합에서 진 쪽은 그 커밋에서 unique 위반을 받아 여기 닿지 않는다.
        log.info {
            "결제 시도 기록 orderId=${event.orderId} checkoutNo=${event.checkoutNo} " +
                "paymentId=${result.paymentId.value} status=${result.status}"
        }
    }

    companion object {
        private val log by logger()
    }
}
