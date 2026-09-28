package com.resilientpos.payment.internal.application

import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.platform.logger
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 시도가 기록된 사실을 받아 provider에 승인을 요청한다.
 *
 * 시도 기록과 provider 호출을 한 consumer에 몰지 않은 이유는 **복구 단서** 때문이다. 둘을 나누면
 * 호출이 실패했을 때 이 메시지가 재시도되고 끝내 DLT에 남아 그대로 단서가 된다. 몰아 두면 이미 커밋된
 * `REQUESTED` 행만 남고 그 행을 다시 집어들 근거가 어디에도 없다.
 *
 * 이 메서드는 트랜잭션이 아니다. 트랜잭션이면 [PaymentManagement]의 적용 트랜잭션이 여기 합류해
 * provider 호출이 트랜잭션 안에 들어간다(도메인 모델 §4.3).
 *
 * 판단은 하지 않는다. 결말이 난 시도를 걸러 내는 것도, 트랜잭션도 [PaymentManagement]가 소유한다.
 * 예외를 삼키지 않는다 — 삼키면 offset이 넘어가 흔적 없이 사라진다. 재시도는 `REQUESTED` 시도로 provider를
 * 다시 부르므로 실제 provider는 `paymentId`를 멱등 키로 받아야 한다(kafka-event-redelivery spec).
 */
@Component
class PaymentAttemptCreatedListener(
    private val paymentManagement: PaymentManagement,
) {

    @KafkaListener(topics = [PaymentEvent.AttemptCreated.TOPIC], groupId = "payment.attempt-created")
    fun on(event: PaymentEvent.AttemptCreated) {
        val result = paymentManagement.authorize(PaymentCommand.Authorize(event.paymentId))

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
