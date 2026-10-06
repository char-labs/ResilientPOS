package com.resilientpos.payment

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.Money

/**
 * 결제 시도의 읽기 투영. aggregate를 거치지 않고 저장된 행에서 바로 만든다.
 * 같은 투영을 여러 조회가 돌려줄 수 있어서 한 조회의 결과 타입으로 묶지 않는다.
 */
data class PaymentAttemptView(
    val paymentId: PaymentId,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val status: PaymentAttemptStatus,
    val amount: Money,
)
