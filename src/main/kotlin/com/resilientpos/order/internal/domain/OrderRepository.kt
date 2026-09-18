package com.resilientpos.order.internal.domain

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.terminal.TerminalId

/**
 * `Order` aggregate를 저장·조회하는 port. 시그니처에 aggregate와 ID 값 타입만 쓴다.
 */
interface OrderRepository {
    fun findById(id: OrderId): Order?
    fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order?
    fun save(order: Order): Order
}
