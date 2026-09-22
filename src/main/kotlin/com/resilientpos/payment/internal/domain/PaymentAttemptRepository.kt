package com.resilientpos.payment.internal.domain

import java.util.UUID

/**
 * `PaymentAttempt`를 저장·조회하는 port. 시그니처에 aggregate와 원시 값만 쓴다.
 */
interface PaymentAttemptRepository {

    /**
     * 그 checkout에 아직 결말이 나지 않은 승인 시도를 읽는다. 같은 이벤트가 다시 와도 시도를 두 번 만들지 않기 위한 조회다.
     * 동시에 들어온 요청까지 막지는 못한다. 그 창은 DB의 partial unique가 닫는다.
     */
    fun findInflightAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt?

    /**
     * 새 시도를 넣는다. 트랜잭션 안에서만 부른다.
     * 같은 checkout에 진행 중인 승인이 이미 있으면 DB 제약이 거절하고 이 호출이 실패한다.
     */
    fun save(attempt: PaymentAttempt): PaymentAttempt
}
