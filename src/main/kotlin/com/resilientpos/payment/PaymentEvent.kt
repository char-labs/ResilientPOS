package com.resilientpos.payment

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID
import org.springframework.modulith.events.Externalized

/**
 * `payment` 모듈 안에 머무는 도메인 이벤트의 닫힌 집합. 모듈을 건너가는 `PaymentApproved`는 `contract`에 있다.
 *
 * `orderId`는 `order.OrderId`가 아니라 raw `UUID`다. `payment`가 `order`에 의존하지 않게 하려는 것이고,
 * 이 모듈의 공개 타입 전부에 같은 이유가 적용된다.
 */
sealed interface PaymentEvent : DomainEvent {
    /**
     * 한 주문의 결제 시도를 만들었다는 사실. 한 주문에 여러 번 날 수 있고 `paymentId`가 그것을 가른다.
     */
    @Externalized(AttemptCreated.TOPIC + "::#{#this.orderId}")
    data class AttemptCreated(
        override val eventId: UUID,
        override val occurredAt: Instant,
        val paymentId: PaymentId,
        val orderId: UUID,
        val storeId: StoreId,
        val terminalId: TerminalId,
        val checkoutNo: Int,
        val kind: PaymentAttemptKind,
        val amount: Money,
    ) : PaymentEvent {
        companion object {
            /** 토픽 이름은 저장 형식이다. 받는 쪽이 같은 모듈이어도 재시도·DLT를 같은 경로로 받으려고 Kafka를 거친다. */
            const val TOPIC = "payment.attempt-created"
        }
    }

    /**
     * provider가 시도를 거절했다는 사실. order는 거절을 받지 않고 주문은 `CHECKOUT_REQUESTED`에 머물러서 `payment`에 남는다.
     * 사유를 담지 않는다 — 소비자가 없어 무엇이 필요한지 정할 근거가 없다.
     */
    data class Declined(
        override val eventId: UUID,
        override val occurredAt: Instant,
        val paymentId: PaymentId,
        val orderId: UUID,
        val checkoutNo: Int,
        val kind: PaymentAttemptKind,
        val amount: Money,
        val providerReference: String?,
    ) : PaymentEvent

    /**
     * provider 결과를 확인하지 못했다는 사실. **실패가 아니다.**
     * 새 승인 요청으로 재시도하지 않고 provider 조회와 reconciliation으로 수렴한다.
     * 수렴은 `payment`의 주기 작업이 provider 조회로 한다. order는 불명을 받지 않고 주문은 `CHECKOUT_REQUESTED`에 머물러서 `payment`에 남는다.
     */
    data class Unknown(
        override val eventId: UUID,
        override val occurredAt: Instant,
        val paymentId: PaymentId,
        val orderId: UUID,
        val checkoutNo: Int,
        val kind: PaymentAttemptKind,
        val amount: Money,
    ) : PaymentEvent
}
