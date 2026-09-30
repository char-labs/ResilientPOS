package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.order.CheckoutId
import com.resilientpos.order.internal.domain.Checkout
import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * 역할: 단말이 결제를 요청한 순간의 합계를 회차(`checkoutNo`)별로 고정한다. 결제 승인은 이 값과 대조한다.
 * 관계: `orderId`로 `OrderEntity`를 가리키고, `PaymentAttemptEntity`가 `checkoutNo`(ID가 아니라 회차 번호)로 이 행을 가리킨다.
 * 흐름: `POST /v1/orders/{id}/checkout`에서 주문 상태 전이와 한 커밋으로 생기고 `OrderPaymentRequested`가 같은 커밋에 기록된다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `Checkout`의 JPA 매핑. 연관관계 매핑 없이 `orderId` 컬럼만 가진 독립 엔티티다.
 * checkout은 확정 뒤에 바뀌지 않으므로 `apply`가 없다. 바뀌지 않는다는 사실은 변경 메서드가 없다는 것으로 표현한다.
 * 전 필드는 `var`에 `protected set`이다. `val`로 두지 않는 이유는 `OrderEntity`의 KDoc에 있다. 생성은 `from`만 한다(도메인 규칙 §5).
 * 통화는 주문의 것을 받아 복원한다.
 */
@Entity
@Table(name = "t_order_checkout")
class OrderCheckoutEntity private constructor(
    id: UUID,
    orderId: UUID,
    checkoutNo: Int,
    amount: BigDecimal,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var orderId: UUID = orderId
        protected set

    var checkoutNo: Int = checkoutNo
        protected set

    var amount: BigDecimal = amount
        protected set

    fun toDomain(currency: Currency): Checkout = Checkout(
        id = CheckoutId(id),
        checkoutNo = checkoutNo,
        amount = Money.of(amount, currency),
    )

    companion object {
        fun from(orderId: UUID, checkout: Checkout): OrderCheckoutEntity =
            OrderCheckoutEntity(checkout.id.value, orderId, checkout.checkoutNo, checkout.amount.amount)
    }
}
