package com.resilientpos.payment

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `payment` 모듈이 발행하는 도메인 이벤트의 닫힌 집합.
 * provider 결과를 적용하는 전이가 생기는 PR이 `PaymentDeclined`·`PaymentUnknown`을 더한다.
 */
sealed interface PaymentEvent : DomainEvent

/**
 * 한 주문의 `attemptNo`회차 결제 시도를 만들었다는 사실.
 */
data class PaymentAttemptCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val attemptNo: Int,
    val amount: Money,
) : PaymentEvent

/**
 * 결제가 승인됐다는 사실. `order`가 이 값을 checkout과 대조하고, 하나라도 다르면 reconciliation case를 연다.
 *
 * `providerReference`는 provider를 실제로 부른 승인에만 있다. 0원 승인은 부르지 않았으므로 `null`이고,
 * 그 `null`이 "provider가 모르는 승인"이라는 사실을 그대로 나타낸다.
 */
data class PaymentApproved(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val attemptNo: Int,
    val amount: Money,
    val providerReference: String?,
) : PaymentEvent
