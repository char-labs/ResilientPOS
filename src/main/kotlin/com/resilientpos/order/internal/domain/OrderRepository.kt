package com.resilientpos.order.internal.domain

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.store.StoreId
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

    /**
     * `id`와 `storeId`가 모두 맞는 주문 행을 잠그고 읽는다. [findByIdForUpdate]와 같은 계약이다. 매장이 다른 행은 잠기지도 반환되지도 않아
     * 다른 매장의 요청이 남의 주문 행을 잠그거나 그 잠금을 기다리지 않는다.
     */
    fun findByIdAndStoreIdForUpdate(id: OrderId, storeId: StoreId): Order?

    /**
     * `(terminalId, clientOrderId)`로 주문 행을 잠그고 읽는다. [findByIdForUpdate]와 같은 계약이다.
     * 트랜잭션 안에서만 부르고, 그 트랜잭션에서 이 주문의 첫 접촉이어야 한다.
     */
    fun findByClientOrderIdForUpdate(terminalId: TerminalId, clientOrderId: ClientOrderId): Order?

    fun findByClientOrderId(terminalId: TerminalId, clientOrderId: ClientOrderId): Order?

    /**
     * aggregate를 저장한다. 트랜잭션이 없으면 이 호출 하나가 한 트랜잭션이 되어 주문과 자식 행이 함께 커밋되거나 함께 롤백된다.
     * 운영 경로는 Facade의 트랜잭션 안에서 부른다. `findByIdForUpdate`가 잡은 잠금은 그 트랜잭션이 끝날 때까지 유지된다.
     */
    fun save(order: Order): Order
}
