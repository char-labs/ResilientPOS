package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * 한 주문의 시도를 생성 순서로 읽는다. 승인 뒤 취소, 거절 뒤 재시도가 모두 같은 `orderId`에 달리므로
 * 결과는 여러 건이고, "마지막 시도가 무엇인가"를 묻는 소비자가 생기므로 순서를 이름에 박는다.
 * `id`는 UUID v7이라 정렬이 곧 생성 순서다.
 *
 * `JpaRepository`를 상속하지 않는다. 그러면 `deleteAllInBatch` 같은 메서드가 공개 빈에 딸려 오는데,
 * `BaseEntity`의 soft delete 때문에 그 한 번이 모든 결제 시도를 조용히 조회에서 지운다.
 * 결제 원장에서 그런 메서드가 열려 있을 이유가 없다.
 */
interface PaymentAttemptJpaRepository : Repository<PaymentAttemptEntity, UUID> {

    fun save(entity: PaymentAttemptEntity): PaymentAttemptEntity

    fun findById(id: UUID): PaymentAttemptEntity?

    fun findAllByOrderIdOrderById(orderId: UUID): List<PaymentAttemptEntity>

    /**
     * 조건이 `ux_payment_attempt_inflight_authorization`의 술어와 같다. 둘이 갈라지면
     * 조회는 없다고 하는데 INSERT는 거절당하는 상태가 된다.
     *
     * 술어의 `deleted_at is null`은 여기 적지 않아도 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
     * 실제로 나가는 SQL이 `... and status in (?,?,?) and deleted_at is null`인 것을 확인했다.
     */
    fun findFirstByOrderIdAndCheckoutNoAndKindAndStatusIn(
        orderId: UUID,
        checkoutNo: Int,
        kind: PaymentAttemptKind,
        statuses: Set<PaymentAttemptStatus>,
    ): PaymentAttemptEntity?
}
