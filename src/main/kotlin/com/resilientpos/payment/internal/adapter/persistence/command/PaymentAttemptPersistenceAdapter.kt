package com.resilientpos.payment.internal.adapter.persistence.command

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.selectFrom
import com.resilientpos.platform.ReadOnlyTransactional
import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * `PaymentAttemptRepository`의 JPA 구현. 조회는 [JdslQueryExecutor]로 한다.
 *
 * 새 행을 `EntityManager.persist`로 넣는다. `PaymentAttemptEntity`는 할당식 `@Id`에 `@Version`이 없어서
 * Spring Data의 `save`가 새 행인지 알 수 없고 `merge`로 가 SELECT가 한 번 더 나간다.
 *
 * 한 주문의 시도는 여럿이다(승인 뒤 취소, 거절 뒤 재시도). `id`가 UUID v7이라 `id` 정렬이 곧 생성 순서다.
 * `deleted_at is null`은 조회마다 적지 않아도 `BaseEntity`의 `@SoftDelete`가 붙여 준다.
 */
@Component
class PaymentAttemptPersistenceAdapter(
    private val entityManager: EntityManager,
    private val queries: JdslQueryExecutor,
) : PaymentAttemptRepository {

    /** 결말로 거르지 않는다 — 거절된 시도도 찾아야 같은 사실이 다시 왔을 때 새 시도를 만들지 않는다. */
    override fun findAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt? =
        queries.findFirst {
            selectFrom(PaymentAttemptEntity::class)
                .where(authorizationOf(orderId, checkoutNo))
                .orderBy(path(PaymentAttemptEntity::id).asc())
        }?.toDomain()

    override fun findLatestAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt? =
        queries.findFirst {
            selectFrom(PaymentAttemptEntity::class)
                .where(authorizationOf(orderId, checkoutNo))
                .orderBy(path(PaymentAttemptEntity::id).desc())
        }?.toDomain()

    @ReadOnlyTransactional
    override fun findById(paymentId: PaymentId): PaymentAttempt? =
        queries.findFirst {
            selectFrom(PaymentAttemptEntity::class)
                .where(path(PaymentAttemptEntity::id).eq(paymentId.value))
        }?.toDomain()

    /**
     * `kind`·`status`를 바인드 파라미터가 아니라 리터럴로 렌더한다(`enumLiteral`). 바인드 파라미터의 generic plan은
     * partial 인덱스 `ix_payment_attempt_unknown_authorization`(V013)의 술어를 증명하지 못해 쓰지 못한다.
     */
    @ReadOnlyTransactional
    override fun findUnknownAuthorizationIds(limit: Int): List<PaymentId> =
        queries.findAll(limit) {
            select(path(PaymentAttemptEntity::id))
                .from(entity(PaymentAttemptEntity::class))
                .where(
                    and(
                        path(PaymentAttemptEntity::kind).eq(enumLiteral(PaymentAttemptKind.AUTHORIZATION)),
                        path(PaymentAttemptEntity::status).eq(enumLiteral(PaymentAttemptStatus.UNKNOWN)),
                    ),
                )
                .orderBy(path(PaymentAttemptEntity::id).asc())
        }.map(::PaymentId)

    override fun findByIdForUpdate(paymentId: PaymentId): PaymentAttempt? =
        queries.findFirstForUpdate {
            selectFrom(PaymentAttemptEntity::class)
                .where(path(PaymentAttemptEntity::id).eq(paymentId.value))
        }?.toDomain()

    /**
     * 영속성 컨텍스트가 관리하는 행을 찾아 결말만 옮긴다. UPDATE는 dirty checking이 커밋에서 낸다.
     * 같은 트랜잭션에서 `findByIdForUpdate`로 이미 잠그고 읽었으면 그 인스턴스가 그대로 돌아오므로 SELECT가 더 나가지 않는다.
     * 행이 없으면 방금 잠근 행이 사라진 것이라 불변식이 깨진 것이다. 클라이언트에 줄 답이 아니어서 도메인 예외로 바꾸지 않는다.
     */
    override fun update(attempt: PaymentAttempt): PaymentAttempt {
        val entity: PaymentAttemptEntity = entityManager.find(PaymentAttemptEntity::class.java, attempt.id.value)
            ?: error("갱신하려는 결제 시도가 없다: ${attempt.id.value}")
        entity.applyResult(attempt.status, attempt.providerReference)
        return attempt
    }

    override fun save(attempt: PaymentAttempt): PaymentAttempt {
        entityManager.persist(PaymentAttemptEntity.from(attempt))
        return attempt
    }
}

private fun Jpql.authorizationOf(orderId: UUID, checkoutNo: Int) =
    and(
        path(PaymentAttemptEntity::orderId).eq(orderId),
        path(PaymentAttemptEntity::checkoutNo).eq(checkoutNo),
        path(PaymentAttemptEntity::kind).eq(PaymentAttemptKind.AUTHORIZATION),
    )
