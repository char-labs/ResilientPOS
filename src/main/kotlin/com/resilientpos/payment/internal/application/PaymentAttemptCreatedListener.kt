package com.resilientpos.payment.internal.application

import com.resilientpos.payment.AuthorizePaymentCommand
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.platform.logger
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * 시도가 기록된 사실을 받아 provider에 승인을 요청한다.
 *
 * 시도 기록과 provider 호출을 한 리스너에 몰지 않은 이유는 **복구 단서** 때문이다. 둘을 나누면
 * 호출이 실패했을 때 이 이벤트의 발행 기록이 `event_publication`에 미완료로 남아, 재전달이 생기는 날
 * 그 기록이 그대로 단서가 된다. 몰아 두면 이미 커밋된 `REQUESTED` 행만 남고 그 행을 다시 집어들
 * 근거가 어디에도 없다.
 *
 * **`propagation = NOT_SUPPORTED`로 트랜잭션을 끈다.** 기본값 `REQUIRES_NEW`면 이 메서드가 트랜잭션이 되고,
 * [PaymentManagement]가 트랜잭션을 나눠도 전부 그 안에 합류해 provider 호출이 다시 트랜잭션 안에 들어간다 —
 * 호출하는 동안 쥔 커넥션이 기본값에서 하나 더 많은 것을 측정했다. 비동기·커밋 뒤 실행과 발행 기록의 추적은
 * 그대로다. 기록은 발행하는 쪽 트랜잭션에서 쓰이고 완료 표시는 Modulith가 반환 뒤 자기 트랜잭션에서 한다.
 * 성공하면 `COMPLETED`, 던지면 `FAILED`로 기본값과 같게 남는 것을 같은 관측 테스트로 확인했다.
 *
 * 판단은 하지 않는다. 결말이 난 시도를 걸러 내는 것도, 트랜잭션도 [PaymentManagement]가 소유한다.
 * 예외를 삼키지 않는다 — 삼키면 Modulith가 성공으로 기록해 미완료 기록마저 사라진다.
 */
@Component
class PaymentAttemptCreatedListener(
    private val paymentManagement: PaymentManagement,
) {

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PaymentAttemptCreated) {
        val result = paymentManagement.authorize(AuthorizePaymentCommand(event.paymentId))

        // 이 메서드가 트랜잭션이 아니라 Facade가 반환했으면 이미 커밋됐다. 그래서 바로 남겨도 참이다.
        log.info {
            "결제 승인 결과 적용 orderId=${event.orderId} checkoutNo=${event.checkoutNo} " +
                "paymentId=${result.paymentId.value} status=${result.status}"
        }
    }

    companion object {
        private val log by logger()
    }
}
