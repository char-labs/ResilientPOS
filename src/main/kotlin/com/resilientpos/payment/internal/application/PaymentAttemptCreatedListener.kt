package com.resilientpos.payment.internal.application

import com.resilientpos.payment.AuthorizePaymentCommand
import com.resilientpos.payment.PaymentAttemptCreated
import com.resilientpos.payment.PaymentManagement
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 시도가 기록된 사실을 받아 provider에 승인을 요청한다.
 *
 * 시도 기록과 provider 호출을 한 리스너에 몰지 않은 이유는 **복구 단서** 때문이다. 둘을 나누면
 * 호출이 실패했을 때 이 이벤트의 발행 기록이 `event_publication`에 미완료로 남아, 재전달이 생기는 날
 * 그 기록이 그대로 단서가 된다. 몰아 두면 이미 커밋된 `REQUESTED` 행만 남고 그 행을 다시 집어들
 * 근거가 어디에도 없다.
 *
 * **`@ApplicationModuleListener`를 쓰지 않는다.** 그것은 `@Async`·`@TransactionalEventListener`에
 * `@Transactional(REQUIRES_NEW)`를 더한 합성이라 이 메서드가 트랜잭션이 되고, [PaymentManagement]가
 * 트랜잭션을 나눠도 전부 이 트랜잭션에 합류해 provider 호출이 다시 그 안에 들어간다. 비동기·커밋 뒤
 * 실행만 가져온다. 발행 기록의 추적은 그대로다 — 기록은 발행하는 쪽 트랜잭션에서 쓰이고 완료 표시는
 * Modulith가 반환 뒤 자기 트랜잭션에서 한다. 성공하면 `COMPLETED`, 던지면 `FAILED`로 남는 것을
 * 두 구조에 같은 관측 테스트를 돌려 확인했다.
 *
 * 판단은 하지 않는다. 결말이 난 시도를 걸러 내는 것도, 트랜잭션도 [PaymentManagement]가 소유한다.
 * 예외를 삼키지 않는다 — 삼키면 Modulith가 성공으로 기록해 미완료 기록마저 사라진다.
 */
@Component
class PaymentAttemptCreatedListener(
    private val paymentManagement: PaymentManagement,
) {

    @Async
    @TransactionalEventListener
    fun on(event: PaymentAttemptCreated) {
        val result = paymentManagement.authorize(AuthorizePaymentCommand(event.paymentId))

        // 이 메서드가 트랜잭션이 아니라 Facade가 반환했으면 이미 커밋됐다. 그래서 바로 남겨도 참이다.
        log.info(
            "결제 승인 결과 적용 orderId={} checkoutNo={} paymentId={} status={}",
            event.orderId,
            event.checkoutNo,
            result.paymentId.value,
            result.status,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaymentAttemptCreatedListener::class.java)
    }
}
