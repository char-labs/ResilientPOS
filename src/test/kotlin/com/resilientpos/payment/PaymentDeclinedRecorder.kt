package com.resilientpos.payment

import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.context.event.EventListener

/** 거절 사실은 소비자가 없어 발행 기록에 남지 않는다. 같은 프로세스에서 발행된 것을 직접 받아 시도별로 센다. */
class PaymentDeclinedRecorder {

    private val received = CopyOnWriteArrayList<PaymentEvent.Declined>()

    @EventListener
    fun record(event: PaymentEvent.Declined) {
        received += event
    }

    fun countFor(paymentId: PaymentId): Int = received.count { it.paymentId == paymentId }
}
