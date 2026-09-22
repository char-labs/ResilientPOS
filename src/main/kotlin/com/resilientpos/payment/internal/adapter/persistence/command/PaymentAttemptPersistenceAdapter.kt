package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.payment.PaymentAttemptKind
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * `PaymentAttemptRepository`의 JPA 구현.
 *
 * 새 행을 `EntityManager.persist`로 넣는다. `PaymentAttemptEntity`는 할당식 `@Id`에 `@Version`이 없어서
 * Spring Data의 `save`가 새 행인지 알 수 없고 `merge`로 가 SELECT가 한 번 더 나간다.
 */
@Component
class PaymentAttemptPersistenceAdapter(
    private val jpaRepository: PaymentAttemptJpaRepository,
    private val entityManager: EntityManager,
) : PaymentAttemptRepository {

    override fun findInflightAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt? =
        jpaRepository.findFirstByOrderIdAndCheckoutNoAndKindAndStatusIn(
            orderId = orderId,
            checkoutNo = checkoutNo,
            kind = PaymentAttemptKind.AUTHORIZATION,
            statuses = PaymentAttempt.INFLIGHT_STATUSES,
        )?.toDomain()

    override fun save(attempt: PaymentAttempt): PaymentAttempt {
        entityManager.persist(PaymentAttemptEntity.from(attempt))
        return attempt
    }
}
