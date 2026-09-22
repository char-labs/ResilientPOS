package com.resilientpos.payment.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 한 주문의 시도를 모두 읽는다. 승인 뒤 취소, 거절 뒤 재시도가 모두 같은 `orderId`에 달리므로
 * 조회 결과는 여러 건이다.
 */
interface PaymentAttemptJpaRepository : JpaRepository<PaymentAttemptEntity, UUID> {

    fun findAllByOrderId(orderId: UUID): List<PaymentAttemptEntity>
}
