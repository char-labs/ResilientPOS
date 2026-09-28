package com.resilientpos.order.internal.domain

import com.resilientpos.order.OrderStatus

/**
 * 주문에서 상태·line·checkout 일부만 바꾼 사본. 전이를 거치지 않은 상태를 만들어야 하는 테스트가 쓴다.
 * 운영 코드에 두면 불변식을 우회하는 길이 되므로 테스트에만 둔다.
 */
fun Order.restored(
    status: OrderStatus = this.status,
    lines: List<OrderLine> = this.lines,
    checkouts: List<Checkout> = this.checkouts,
): Order = Order.restore(id, storeId, terminalId, clientOrderId, currency, status, lines, checkouts)
