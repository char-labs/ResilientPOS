package com.resilientpos.order.internal.adapter.persistence.command

import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.QueryHints

/**
 * `t_order`용 Spring Data repository. `OrderPersistenceAdapter`만 사용한다.
 */
interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
    fun findByTerminalIdAndClientOrderId(terminalId: UUID, clientOrderId: UUID): OrderEntity?

    /**
     * PostgreSQL에서는 `select ... for no key update`다. 같은 잠금끼리 충돌하므로 같은 주문의 전이가 직렬화된다.
     * 대기는 3초로 제한한다. Hibernate가 조회 앞뒤로 `set local lock_timeout`을 걸고 되돌린다.
     * 넘으면 `PessimisticLockingFailureException` 계열 예외다. 무기한 대기는 커넥션을 쥔 채 쌓여 풀을 말린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = ORDER_LOCK_TIMEOUT_MILLIS))
    fun findWithLockById(id: UUID): OrderEntity?
}

private const val ORDER_LOCK_TIMEOUT_MILLIS = "3000"
