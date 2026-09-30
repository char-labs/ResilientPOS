package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * 역할: 판매 시점의 품목 한 줄(상품 코드·이름·단가·수량)을 그때 값 그대로 남긴다. 상품 마스터가 없어 이 행이 "무엇을 얼마에 팔았는가"의 원본이다.
 * 관계: `orderId`로 `OrderEntity`를 가리킨다. 통화는 주문의 것을 받아 복원한다.
 * 흐름: 주문 생성 때 루트와 한 커밋으로 생긴다. line을 고치는 명령은 아직 없다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `OrderLine`의 JPA 매핑. 연관관계 매핑 없이 `orderId` 컬럼만 가진 독립 엔티티다.
 * 생성은 `from`, 변경은 `apply`만 한다(도메인 규칙 §5). 전 필드가 `var`에 `protected set`이다.
 * `id`와 `orderId`는 `apply`가 바꾸지 않지만 `val`로 두지 않는다. 이유는 `OrderEntity`의 KDoc에 있다.
 * 주문과의 조립과 변경 반영은 `OrderPersistenceAdapter`가 한다. `lineNo`는 정렬용이다.
 */
@Entity
@Table(name = "t_order_line")
class OrderLineEntity private constructor(
    id: UUID,
    orderId: UUID,
    lineNo: Int,
    productCode: String,
    productName: String,
    unitPrice: BigDecimal,
    quantity: Int,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var orderId: UUID = orderId
        protected set

    var lineNo: Int = lineNo
        protected set

    var productCode: String = productCode
        protected set

    var productName: String = productName
        protected set

    var unitPrice: BigDecimal = unitPrice
        protected set

    var quantity: Int = quantity
        protected set

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
        unitPrice = Money.of(unitPrice, currency),
        quantity = quantity,
    )

    companion object {
        fun from(orderId: UUID, lineNo: Int, line: OrderLine): OrderLineEntity =
            OrderLineEntity(line.id.value, orderId, lineNo, line.productCode, line.productName, line.unitPrice.amount, line.quantity)
    }
}
