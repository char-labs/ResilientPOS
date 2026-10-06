package com.resilientpos.payment.internal.adapter.persistence.query

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentAttemptView
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.adapter.persistence.command.PaymentAttemptEntity
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.store.StoreId
import java.util.Currency
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * 결제 시도 조회 구현. aggregate를 거치지 않고 엔티티를 바로 [PaymentAttemptView]로 바꾼다.
 * 구현이 하나뿐이고 View는 aggregate 경계에 들어가지 않아서 port 인터페이스를 두지 않는다.
 */
@Component
class PaymentAttemptQueries(
    private val queries: JdslQueryExecutor,
) {

    /** `id`가 UUID v7이라 오름차순이 곧 생성 순이다. `ix_payment_attempt_order (order_id, id)`를 탄다. */
    @ReadOnlyTransactional
    fun findAttempts(orderId: UUID, storeId: StoreId): List<PaymentAttemptView> =
        queries.findAll {
            selectFrom(PaymentAttemptEntity::class)
                .where(
                    path(PaymentAttemptEntity::orderId).eq(orderId)
                        .and(path(PaymentAttemptEntity::storeId).eq(storeId.value)),
                )
                .orderBy(path(PaymentAttemptEntity::id).asc())
        }.map { it.toView() }
}

private fun PaymentAttemptEntity.toView() = PaymentAttemptView(
    paymentId = PaymentId(id),
    checkoutNo = checkoutNo,
    kind = kind,
    status = status,
    amount = Money.of(amount, Currency.getInstance(currency)),
)
