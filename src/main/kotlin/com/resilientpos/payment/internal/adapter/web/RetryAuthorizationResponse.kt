package com.resilientpos.payment.internal.adapter.web

import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import java.util.UUID

/**
 * `POST /v1/payment-attempts/{paymentId}/retry`의 성공 응답 본문. 새 시도의 결말은 조회 API로 본다.
 */
data class RetryAuthorizationResponse(
    val paymentId: UUID,
    val status: PaymentAttemptStatus,
) {
    companion object {
        fun from(result: PaymentCommand.RetryAuthorization.Result) = RetryAuthorizationResponse(
            paymentId = result.paymentId.value,
            status = result.status,
        )
    }
}
