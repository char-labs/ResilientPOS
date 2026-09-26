package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.contract.PaymentAttemptKind
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import java.util.UUID
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.QueryHints
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
     * 그 checkout의 승인 시도 중 가장 먼저 만든 것. 결말로 거르지 않는다 — 거절된 시도도 찾아야
     * 같은 사실이 다시 왔을 때 새 시도를 만들지 않는다.
     *
     * `deleted_at is null`은 여기 적지 않아도 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
     */
    fun findFirstByOrderIdAndCheckoutNoAndKindOrderById(
        orderId: UUID,
        checkoutNo: Int,
        kind: PaymentAttemptKind,
    ): PaymentAttemptEntity?

    /** 그 checkout의 승인 시도 중 가장 나중에 만든 것. `id`가 UUID v7이라 역순 정렬이 곧 생성 역순이다. */
    fun findFirstByOrderIdAndCheckoutNoAndKindOrderByIdDesc(
        orderId: UUID,
        checkoutNo: Int,
        kind: PaymentAttemptKind,
    ): PaymentAttemptEntity?

    /**
     * PostgreSQL에서는 `select ... for no key update`다. 같은 시도에 결과를 적용하는 트랜잭션이 직렬화된다.
     * 대기는 3초로 제한한다. `OrderJpaRepository.findWithLockById`와 같은 이유다 — 무기한 대기는 커넥션을 쥔 채 쌓여 풀을 말린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = PAYMENT_ATTEMPT_LOCK_TIMEOUT_MILLIS))
    fun findWithLockById(id: UUID): PaymentAttemptEntity?
}

internal const val PAYMENT_ATTEMPT_LOCK_TIMEOUT_MILLIS = "3000"
