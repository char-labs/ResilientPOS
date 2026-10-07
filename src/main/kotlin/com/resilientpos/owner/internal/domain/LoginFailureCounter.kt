package com.resilientpos.owner.internal.domain

/**
 * 이메일별 로그인 실패 횟수 port. 횟수는 업무 사실이 아니라 시한이 있는 제한 장치다.
 * 저장소 장애는 구현이 삼킨다(fail-open): 장애 중에는 [count]가 0이다.
 */
interface LoginFailureCounter {

    /** 지금 창 안의 실패 횟수. */
    fun count(email: OwnerEmail): Long

    /** 실패를 하나 센다. 창의 첫 실패가 창을 연다. */
    fun increment(email: OwnerEmail)

    /** 횟수를 지운다. */
    fun clear(email: OwnerEmail)
}
