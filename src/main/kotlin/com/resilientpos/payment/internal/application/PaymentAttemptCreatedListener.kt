package com.resilientpos.payment.internal.application

import com.resilientpos.payment.AuthorizePaymentCommand
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentManagement
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 시도가 기록된 사실을 받아 provider에 승인을 요청한다.
 *
 * 시도 기록과 provider 호출을 한 리스너에 몰지 않은 이유는 **복구 단서** 때문이다. 둘을 나누면
 * 호출이 실패했을 때 이 이벤트의 발행 기록이 `event_publication`에 미완료로 남아, 재전달이 생기는 날
 * 그 기록이 그대로 단서가 된다. 몰아 두면 이미 커밋된 `REQUESTED` 행만 남고 그 행을 다시 집어들
 * 근거가 어디에도 없다.
 *
 * `PaymentAttemptCreated`는 지금까지 받는 쪽이 없어 발행돼도 기록되지 않았다. 이 리스너가 첫 소비자이고,
 * **그 순간부터 이 이벤트가 `event_publication`에 기록된다.**
 *
 * 판단은 하지 않는다. 결말이 난 시도를 걸러 내는 것도, 트랜잭션도 [PaymentManagement]가 소유한다.
 * 예외를 삼키지 않는다 — 삼키면 Modulith가 성공으로 기록해 미완료 기록마저 사라진다.
 */
@Component
class PaymentAttemptCreatedListener(
    private val paymentManagement: PaymentManagement,
) {

    @ApplicationModuleListener
    fun on(event: PaymentAttemptCreated) {
        val result = paymentManagement.authorize(AuthorizePaymentCommand(event.paymentId))

        afterCommit {
            log.info(
                "결제 승인 결과 적용 orderId={} checkoutNo={} paymentId={} status={}",
                event.orderId,
                event.checkoutNo,
                result.paymentId.value,
                result.status,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaymentAttemptCreatedListener::class.java)
    }
}
