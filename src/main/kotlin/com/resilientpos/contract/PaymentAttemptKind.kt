package com.resilientpos.contract

/**
 * 결제 시도가 provider에게 **무엇을 요청했는지**.
 *
 * `payment`가 소유하는 개념이지만 [PaymentApproved]의 payload라서 계약에 둔다.
 * 이 값이 없으면 `PaymentApproved`를 받는 쪽이 **환불 승인을 결제 승인으로 읽어** 주문을 결제 완료로 넘긴다.
 */
enum class PaymentAttemptKind {
    /** 결제를 승인받으려는 요청. */
    AUTHORIZATION,

    /** 이미 승인된 결제를 되돌리려는 요청. 취소와 환불이 모두 여기에 온다. */
    CANCELLATION,
}
