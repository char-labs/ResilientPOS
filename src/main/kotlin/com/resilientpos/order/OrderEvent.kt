package com.resilientpos.order

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `order` 모듈이 발행하는 도메인 이벤트의 닫힌 집합.
 */
sealed interface OrderEvent : DomainEvent

/**
 * 주문이 `OPEN` 상태로 생성됐다는 사실.
 */
data class OrderCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val orderId: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val clientOrderId: ClientOrderId,
) : OrderEvent

/**
 * 주문이 `checkoutNo`회차 결제를 `amount`로 확정해 요청했다는 사실. `payment`가 이 값으로 attempt를 만든다.
 * `(orderId, checkoutNo)`는 같은 checkout에 대한 승인 attempt를 두 번 만들지 않는 근거다.
 */
data class OrderPaymentRequested(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val orderId: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val amount: Money,
) : OrderEvent
