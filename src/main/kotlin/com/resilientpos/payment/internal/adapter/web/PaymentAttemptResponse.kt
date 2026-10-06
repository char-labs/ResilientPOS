package com.resilientpos.payment.internal.adapter.web

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentAttemptView
import java.util.UUID

/**
 * `GET /v1/orders/{orderId}/payment-attempts`의 목록 항목. `amount`는 통화 기준 decimal string이다.
 */
data class PaymentAttemptResponse(
    val paymentId: UUID,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val status: PaymentAttemptStatus,
    val amount: String,
    val currency: String,
) {
    companion object {
        fun from(view: PaymentAttemptView) = PaymentAttemptResponse(
            paymentId = view.paymentId.value,
            checkoutNo = view.checkoutNo,
            kind = view.kind,
            status = view.status,
            amount = view.amount.amount.toPlainString(),
            currency = view.amount.currency.currencyCode,
        )
    }
}
