package com.resilientpos.contract

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID
import org.springframework.modulith.events.Externalized

/**
 * 시도가 승인됐다는 사실. `order`가 이 값을 checkout과 대조하고, 하나라도 다르면 reconciliation case를 연다.
 *
 * **[kind]를 보지 않고 결제 완료로 읽으면 안 된다.** 취소가 승인돼도 이 이벤트가 난다.
 *
 * `providerReference`는 provider를 실제로 부른 승인에만 있다. 0원 승인은 부르지 않았으므로 `null`이고,
 * 그 `null`이 "provider가 모르는 승인"이라는 사실을 그대로 나타낸다.
 */
@Externalized(PaymentApproved.TOPIC + "::#{#this.orderId}")
data class PaymentApproved(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: UUID,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val amount: Money,
    val providerReference: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
) : DomainEvent {
    init {
        requireKnownSchemaVersion(javaClass.simpleName, schemaVersion, SCHEMA_VERSION)
    }

    companion object {
        /** 토픽 이름은 저장 형식이다. key는 `orderId`라 같은 주문의 메시지가 한 파티션에 차례로 선다(도메인 모델 §4.3). */
        const val TOPIC = "payment.approved"

        /** 형식 버전. 호환되지 않는 변경에만 올린다. 필드 추가는 올리지 않는다(docs/wiki/event-contract.md). */
        const val SCHEMA_VERSION = 1
    }
}
