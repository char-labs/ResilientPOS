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
