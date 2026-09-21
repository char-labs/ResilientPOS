package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.Money

/**
 * 결제 시도 `checkoutNo`회차에 대해 확정한 금액. 확정 뒤에는 바뀌지 않는다.
 */
data class Checkout(
    val checkoutNo: Int,
    val amount: Money,
)
