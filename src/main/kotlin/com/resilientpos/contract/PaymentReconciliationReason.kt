package com.resilientpos.contract

/**
 * 결제 시도를 reconciliation case로 넘긴 **사유**. 넘길 때 정하고 그 뒤로 바꾸지 않는다.
 *
 * `payment`가 소유하는 개념이지만 넘김 사실([PaymentReconciliationRequested])이 싣고 `reconciliation`이 읽으므로 계약에 둔다.
 */
enum class PaymentReconciliationReason {
    /** 조회 결과가 불명인 채로 조회 횟수가 상한에 닿았다. */
    QUERY_LIMIT_EXCEEDED,

    /** 조회 결과가 승인인데 provider가 알려 준 승인 금액이 시도 금액과 다르다. */
    AMOUNT_MISMATCH,
}
