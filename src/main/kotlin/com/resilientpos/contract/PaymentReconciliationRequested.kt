package com.resilientpos.contract

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID
import org.springframework.modulith.events.Externalized

/**
 * 결과를 모르던(`UNKNOWN`) 시도를 조회로 확정하지 못해 reconciliation case로 넘겼다는 사실. `occurredAt`은 넘긴 시각이다.
 * 이 시도는 이 사실 뒤로 `payment`가 더 조회하지 않고 reconciliation의 결론으로만 확정된다.
 *
 * [amount]는 시도 금액이다. [reportedAmount]·[reportedProviderReference]는 [reason]이 [PaymentReconciliationReason.AMOUNT_MISMATCH]일 때
 * 조회가 알려 준 승인 금액과 참조이고, 아니면 `null`이다. [queryCount]는 넘긴 시점까지 조회한 횟수다.
 */
@Externalized(PaymentReconciliationRequested.TOPIC + "::#{#this.orderId}")
data class PaymentReconciliationRequested(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: UUID,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val amount: Money,
    val reason: PaymentReconciliationReason,
    val unknownReason: PaymentUnknownReason,
    val queryCount: Int,
    val reportedAmount: Money?,
    val reportedProviderReference: String?,
) : DomainEvent {
    companion object {
        /** 토픽 이름은 저장 형식이다. key는 `orderId`라 같은 주문의 메시지가 한 파티션에 차례로 선다(도메인 모델 §4.3). */
        const val TOPIC = "payment.reconciliation-requested"
    }
}
