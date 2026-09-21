package com.resilientpos.order.internal.domain

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.terminal.TerminalId

/**
 * `Order` aggregate를 저장·조회하는 port. 시그니처에 aggregate와 ID 값 타입만 쓴다.
 */
interface OrderRepository {
    fun findById(id: OrderId): Order?

    /**
     * 주문 행을 잠그고 읽는다. 읽고 판단한 뒤 쓰는 전이가 같은 주문에서 겹치지 않게 한다. 트랜잭션 안에서만 부른다.
     * 그 트랜잭션에서 이 주문의 첫 접촉이어야 한다. 먼저 `findById`로 읽었고 그 사이에 다른 트랜잭션이 주문을 바꿨으면
     * 구현이 낡은 값을 돌려주지 않고 낙관적 잠금 예외로 실패한다.
     */
    fun findByIdForUpdate(id: OrderId): Order?

    fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order?
    fun save(order: Order): Order
}
