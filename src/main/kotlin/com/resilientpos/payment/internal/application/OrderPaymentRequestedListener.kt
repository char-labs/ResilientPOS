package com.resilientpos.payment.internal.application

import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.payment.CreatePaymentAttemptCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.platform.logger
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * `order`가 결제를 요청한 사실을 받아 시도를 기록한다. 이 저장소의 첫 프로덕션 리스너다.
 *
 * 옮기는 일만 하고 판단하지 않는다. 멱등 판정과 트랜잭션은 [PaymentManagement]가 소유한다.
 *
 * 비동기이고 커밋 뒤에 돈다. `@ApplicationModuleListener`를 쓰지 않는 이유는
 * [PaymentAttemptCreatedListener]와 같다 — 이 메서드가 트랜잭션이 아니어야 Facade가 자기 트랜잭션을
 * 열고 닫아, 반환하는 순간이 곧 커밋 뒤가 된다.
 *
 * 여기서 실패하면 `event_publication`에 미완료로 남고 자동 재시도가 없다. 재전달은 Kafka를 들이는 설계가
 * 맡는다. 그래서 예외를 삼키지 않는다 — 삼키면 성공으로 기록되어 미완료 기록마저 사라진다.
 */
@Component
class OrderPaymentRequestedListener(
    private val paymentManagement: PaymentManagement,
) {

    @Async
    @TransactionalEventListener
    fun on(event: OrderPaymentRequested) {
        val result = paymentManagement.createAttempt(
            CreatePaymentAttemptCommand(
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
