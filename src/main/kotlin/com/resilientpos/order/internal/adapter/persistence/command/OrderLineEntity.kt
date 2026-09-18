package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.UUID

/**
 * `OrderLine`의 JPA 매핑. 연관관계 매핑 없이 `orderId` 컬럼만 가진 독립 엔티티다.
 * 주문과의 조립과 변경 반영은 `OrderPersistenceAdapter`가 한다. `lineNo`는 정렬용이다.
 */
@Entity
@Table(name = "t_order_line")
class OrderLineEntity(
    @Id
    var id: UUID = UUID(0, 0),
    var orderId: UUID = UUID(0, 0),
    var lineNo: Int = 0,
    var productCode: String = "",
    var productName: String = "",
    var unitPrice: BigDecimal = BigDecimal.ZERO,
    var quantity: Int = 0,
) : BaseEntity() {

    fun apply(lineNo: Int, line: OrderLine) {
        this.lineNo = lineNo
        productCode = line.productCode
        productName = line.productName
        unitPrice = line.unitPrice.amount
        quantity = line.quantity
    }

    fun toDomain(currency: Currency): OrderLine = OrderLine(
        id = OrderLineId(id),
        productCode = productCode,
        productName = productName,
        unitPrice = Money(unitPrice.setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY), currency),
        quantity = quantity,
    )

    companion object {
        fun from(orderId: UUID, lineNo: Int, line: OrderLine): OrderLineEntity =
            OrderLineEntity(line.id.value, orderId).also { it.apply(lineNo, line) }
    }
}
