package com.resilientpos.contract

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * 주문이 `checkoutNo`회차 결제를 `amount`로 확정해 요청했다는 사실. `payment`가 이 값으로 시도를 만든다.
 * `(orderId, checkoutNo)`는 같은 checkout에 승인 시도를 두 번 만들지 않는 근거다.
 *
 * `orderId`가 `order.OrderId`가 아니라 raw `UUID`다. 계약이 업무 모듈의 타입을 들면
 * 그 방향으로 간선이 생겨 끊으려던 순환이 되돌아온다. 받는 쪽이 자기 타입으로 감싼다.
 */
data class OrderPaymentRequested(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val amount: Money,
) : DomainEvent
