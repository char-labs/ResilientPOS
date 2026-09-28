package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderLineId

/**
 * 주문 line 자식 값. 판매 시점 상품 코드·이름·단가·수량을 기록한다.
 */
data class OrderLine(
    val id: OrderLineId,
    val productCode: String,
    val productName: String,
    val unitPrice: Money,
    val quantity: Int,
) {
    init {
        if (productCode.isBlank()) {
            throw DomainException(BLANK_PRODUCT_CODE, "상품 코드는 비어 있을 수 없습니다")
        }
        if (productName.isBlank()) {
            throw DomainException(BLANK_PRODUCT_NAME, "상품명은 비어 있을 수 없습니다")
        }
        if (quantity < 1) {
            throw DomainException(INVALID_QUANTITY, "수량은 1 이상이어야 합니다")
        }
        if (unitPrice.isNegative) {
            throw DomainException(NEGATIVE_UNIT_PRICE, "단가는 음수일 수 없습니다")
        }
    }

    val amount: Money get() = unitPrice * quantity

    companion object {
        const val BLANK_PRODUCT_CODE = "BLANK_PRODUCT_CODE"
        const val BLANK_PRODUCT_NAME = "BLANK_PRODUCT_NAME"
        const val INVALID_QUANTITY = "INVALID_QUANTITY"
        const val NEGATIVE_UNIT_PRICE = "NEGATIVE_UNIT_PRICE"
    }
}
