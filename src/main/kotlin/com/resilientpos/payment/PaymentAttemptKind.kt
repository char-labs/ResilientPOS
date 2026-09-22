package com.resilientpos.payment

/**
 * 시도가 provider에게 **무엇을 요청했는지**. 상태([PaymentAttemptStatus])는 그 요청의 답이 무엇이었는지를 말한다.
 *
 * 한 주문에 시도가 여럿 달리고 그중에는 승인도 있고 취소도 있다. 구분자가 없으면 두 가지가 무너진다.
 * `(orderId, attemptNo)`만으로는 저장된 행이 무엇을 요청한 것인지 알 수 없고,
 * 무엇보다 `PaymentApproved`를 받는 `order`가 **환불 승인을 결제 승인으로 읽어** 주문을 결제 완료로 넘긴다.
 */
enum class PaymentAttemptKind {
    /** 결제를 승인받으려는 요청. */
    AUTHORIZATION,

    /** 이미 승인된 결제를 되돌리려는 요청. 취소와 환불이 모두 여기에 온다. */
    CANCELLATION,
}
