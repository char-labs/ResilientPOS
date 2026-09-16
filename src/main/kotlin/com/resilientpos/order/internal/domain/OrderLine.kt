package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.Money

/**
 * 주문 line 자식 값. 판매 시점 상품 코드·이름·단가·수량을 기록한다.
 */
data class OrderLine(
    val productCode: String,
    val productName: String,
    val unitPrice: Money,
    val quantity: Int,
) {
    init {
        require(quantity >= 1) { INVALID_QUANTITY }
        require(unitPrice >= Money.zero(unitPrice.currency)) { NEGATIVE_UNIT_PRICE }
    }

    val amount: Money get() = unitPrice * quantity

    companion object {
        const val INVALID_QUANTITY = "INVALID_QUANTITY"
        const val NEGATIVE_UNIT_PRICE = "NEGATIVE_UNIT_PRICE"
    }
}
