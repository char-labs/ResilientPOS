package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.payment.PaymentId
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

    override fun findAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt? =
        jpaRepository.findFirstByOrderIdAndCheckoutNoAndKindOrderById(
            orderId = orderId,
            checkoutNo = checkoutNo,
            kind = PaymentAttemptKind.AUTHORIZATION,
        )?.toDomain()

    override fun findLatestAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt? =
        jpaRepository.findFirstByOrderIdAndCheckoutNoAndKindOrderByIdDesc(
            orderId = orderId,
            checkoutNo = checkoutNo,
            kind = PaymentAttemptKind.AUTHORIZATION,
        )?.toDomain()

    override fun findById(paymentId: PaymentId): PaymentAttempt? =
        jpaRepository.findById(paymentId.value)?.toDomain()

    override fun findByIdForUpdate(paymentId: PaymentId): PaymentAttempt? =
        jpaRepository.findWithLockById(paymentId.value)?.toDomain()

    /**
     * 영속성 컨텍스트가 관리하는 행을 찾아 결말만 옮긴다. UPDATE는 dirty checking이 커밋에서 낸다.
     * 같은 트랜잭션에서 `findByIdForUpdate`로 이미 잠그고 읽었으면 그 인스턴스가 그대로 돌아오므로 SELECT가 더 나가지 않는다.
     * 행이 없으면 방금 잠근 행이 사라진 것이라 불변식이 깨진 것이다. 클라이언트에 줄 답이 아니어서 도메인 예외로 바꾸지 않는다.
     */
    override fun update(attempt: PaymentAttempt): PaymentAttempt {
        val entity = jpaRepository.findById(attempt.id.value)
            ?: error("갱신하려는 결제 시도가 없다: ${attempt.id.value}")
        entity.applyResult(attempt.status, attempt.providerReference)
        return attempt
    }

    override fun save(attempt: PaymentAttempt): PaymentAttempt {
        entityManager.persist(PaymentAttemptEntity.from(attempt))
        return attempt
    }
}
