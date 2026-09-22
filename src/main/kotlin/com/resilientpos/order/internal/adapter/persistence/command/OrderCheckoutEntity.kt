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
 * checkout은 확정 뒤에 바뀌지 않으므로 `apply`가 없고 전 필드가 `val`이다. 통화는 주문의 것을 받아 복원한다.
 * 생성은 `from`만 한다(도메인 규칙 §5).
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
    val id: UUID = id

    val orderId: UUID = orderId

    val checkoutNo: Int = checkoutNo

    val amount: BigDecimal = amount

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
