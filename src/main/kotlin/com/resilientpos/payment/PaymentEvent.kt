package com.resilientpos.payment

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `payment` 모듈 안에 머무는 도메인 이벤트의 닫힌 집합. 모듈을 건너가는 `PaymentApproved`는 `contract`에 있다.
 *
 * `orderId`는 `order.OrderId`가 아니라 raw `UUID`다. `payment`가 `order`에 의존하지 않게 하려는 것이고,
 * 이 모듈의 공개 타입 전부에 같은 이유가 적용된다.
 */
sealed interface PaymentEvent : DomainEvent

/**
 * 한 주문의 결제 시도를 만들었다는 사실. 한 주문에 여러 번 날 수 있고 `paymentId`가 그것을 가른다.
 */
data class PaymentAttemptCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val amount: Money,
) : PaymentEvent

/**
 * provider가 시도를 거절했다는 사실. 받을 쪽이 아직 없어 `payment`에 남는다.
 * 사유를 담지 않는다 — 소비자가 없어 무엇이 필요한지 정할 근거가 없다.
 */
data class PaymentDeclined(
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
 * 그 수렴을 맡을 `reconciliation`이 비어 있어 받을 쪽이 아직 없고, 그래서 `payment`에 남는다.
 */
data class PaymentUnknown(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: UUID,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val amount: Money,
) : PaymentEvent
