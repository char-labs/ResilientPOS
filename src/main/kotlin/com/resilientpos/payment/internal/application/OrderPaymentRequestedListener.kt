package com.resilientpos.payment.internal.application

import com.resilientpos.order.OrderPaymentRequested
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentManagement
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * `order`가 결제를 요청한 사실을 받아 시도를 기록한다. 이 저장소의 첫 프로덕션 리스너다.
 *
 * 옮기는 일만 하고 판단하지 않는다. 멱등 판정과 트랜잭션은 [PaymentManagement]가 소유한다.
 *
 * `@ApplicationModuleListener`는 비동기이고 커밋 뒤에 자기 트랜잭션에서 돈다. 여기서 실패하면
 * `event_publication`에 미완료로 남고 자동 재시도가 없다. 재전달은 Kafka를 들이는 설계가 맡는다.
 * 그래서 예외를 삼키지 않는다 — 삼키면 성공으로 기록되어 미완료 기록마저 사라진다.
 *
 * 아래 로그는 **커밋 전에** 찍힌다. 이 메서드 전체가 트랜잭션 안이라 메서드 안에서 커밋 뒤를 볼 수 없다.
 * 커밋이 실패하면 로그만 남고 행은 없으므로, 무엇이 실제로 저장됐는지는 `event_publication`의 완료 여부로 본다.
 */
@Component
class OrderPaymentRequestedListener(
    private val paymentManagement: PaymentManagement,
) {

    @ApplicationModuleListener
    fun on(event: OrderPaymentRequested) {
        val result = paymentManagement.createAttempt(
            CreatePaymentAttemptCommand(
                orderId = event.orderId.value,
                storeId = event.storeId,
                terminalId = event.terminalId,
                checkoutNo = event.checkoutNo,
                amount = event.amount,
            ),
        )
        log.info(
            "결제 시도 기록 orderId={} checkoutNo={} paymentId={} status={}",
            event.orderId.value,
            event.checkoutNo,
            result.paymentId.value,
            result.status,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderPaymentRequestedListener::class.java)
    }
}
