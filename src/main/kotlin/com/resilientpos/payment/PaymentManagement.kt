package com.resilientpos.payment

/**
 * `payment` 모듈의 유일한 진입점. 결제 정본 상태를 바꾸는 일은 전부 여기를 거친다.
 * 구현과 트랜잭션 경계는 영속화 PR이 만든다.
 */
interface PaymentManagement {

    fun createAttempt(command: CreatePaymentAttemptCommand): CreatePaymentAttemptResult
}
