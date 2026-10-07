package com.resilientpos.owner.internal.domain

/**
 * 이메일별 로그인 시도 횟수 port. 횟수는 업무 사실이 아니라 시한이 있는 제한 장치다.
 * 저장소 장애는 구현이 삼킨다(fail-open): 장애 중에는 [increment]가 0이다.
 */
interface LoginFailureCounter {

    /** 시도를 하나 세고 센 뒤의 창 안 횟수를 돌려준다. 창의 첫 시도가 창을 연다. 동시 호출은 서로 다른 값을 받는다. */
    fun increment(email: OwnerEmail): Long

    /** 횟수를 지운다. */
    fun clear(email: OwnerEmail)
}
