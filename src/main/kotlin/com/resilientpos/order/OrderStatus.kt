package com.resilientpos.order

/**
 * 주문 lifecycle 상태. `OPEN` 이외의 상태는 각 명령 PR이 추가한다.
 */
enum class OrderStatus {
    OPEN,
    CHECKOUT_REQUESTED,

    /** 요청한 checkout 회차의 결제가 승인됐다. */
    PAID,
}
