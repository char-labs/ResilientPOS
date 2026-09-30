package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.isNegative
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
        // PostgreSQL text가 받지 못하는 문자는 NUL 하나라 그것만 본다.
        if (productCode.contains(NUL)) {
            throw DomainException(INVALID_PRODUCT_CODE, "상품 코드에 쓸 수 없는 문자가 있습니다")
        }
        if (productName.contains(NUL)) {
            throw DomainException(INVALID_PRODUCT_NAME, "상품명에 쓸 수 없는 문자가 있습니다")
        }
        if (productCode.codePointCount(0, productCode.length) > MAX_PRODUCT_CODE_LENGTH) {
            throw DomainException(PRODUCT_CODE_TOO_LONG, "상품 코드는 ${MAX_PRODUCT_CODE_LENGTH}자를 넘을 수 없습니다")
        }
        if (productName.codePointCount(0, productName.length) > MAX_PRODUCT_NAME_LENGTH) {
            throw DomainException(PRODUCT_NAME_TOO_LONG, "상품명은 ${MAX_PRODUCT_NAME_LENGTH}자를 넘을 수 없습니다")
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
        private const val NUL = '\u0000'

        const val BLANK_PRODUCT_CODE = "BLANK_PRODUCT_CODE"
        const val BLANK_PRODUCT_NAME = "BLANK_PRODUCT_NAME"
        const val INVALID_PRODUCT_CODE = "INVALID_PRODUCT_CODE"
        const val INVALID_PRODUCT_NAME = "INVALID_PRODUCT_NAME"
        const val INVALID_QUANTITY = "INVALID_QUANTITY"
        const val NEGATIVE_UNIT_PRICE = "NEGATIVE_UNIT_PRICE"
        const val PRODUCT_CODE_TOO_LONG = "PRODUCT_CODE_TOO_LONG"
        const val PRODUCT_NAME_TOO_LONG = "PRODUCT_NAME_TOO_LONG"

        /**
         * 컬럼 `t_order_line.product_code varchar(64)`·`product_name varchar(255)`(V002)를 따라간다. 컬럼을 바꾸면 함께 바꾼다.
         * 길이는 PostgreSQL `varchar(n)`처럼 문자(code point) 수로 센다. 저장된 행을 읽을 때도 이 검사를 지나므로 컬럼보다 작게 두면
         * 이미 저장된 주문이 읽히지 않는다.
         */
        const val MAX_PRODUCT_CODE_LENGTH = 64
        const val MAX_PRODUCT_NAME_LENGTH = 255
    }
}
