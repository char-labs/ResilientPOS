package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.order.internal.domain.OrderLine
import jakarta.persistence.Embeddable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * `OrderLine`의 JPA 매핑. `@ElementCollection`으로 `t_order_line`에 저장된다.
 * JPA가 관리하는 값이라 `var`를 허용한다(도메인 규칙 §5의 예외는 엔티티·임베더블에 한한다).
 */
@Embeddable
class OrderLineJpaValue(
    var productCode: String = "",
    var productName: String = "",
    var unitPrice: BigDecimal = BigDecimal.ZERO,
    var quantity: Int = 0,
) {

    fun toDomain(currency: Currency): OrderLine = OrderLine(
        productCode = productCode,
        productName = productName,
        unitPrice = Money(unitPrice.setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY), currency),
        quantity = quantity,
    )

    companion object {
        fun from(line: OrderLine): OrderLineJpaValue =
            OrderLineJpaValue(line.productCode, line.productName, line.unitPrice.amount, line.quantity)
    }
}
