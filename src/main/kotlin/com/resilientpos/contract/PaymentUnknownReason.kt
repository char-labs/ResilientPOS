package com.resilientpos.contract

/**
 * 결제 시도가 `UNKNOWN`이 된 **사유**. `UNKNOWN`이 된 때 정하고 그 뒤로 바꾸지 않는다.
 *
 * `payment`가 소유하는 개념이지만 `payment`의 aggregate와 넘김 사실이 함께 쓰므로 [PaymentAttemptKind]처럼 계약에 둔다.
 */
enum class PaymentUnknownReason {
    /** 전송 실패·시간 초과처럼 provider의 결과를 받지 못했다. */
    RESULT_UNCONFIRMED,

    /** 승인이라는 결과는 받았지만 provider가 말한 승인 금액이 시도 금액과 다르다. */
    AMOUNT_MISMATCH,
}
