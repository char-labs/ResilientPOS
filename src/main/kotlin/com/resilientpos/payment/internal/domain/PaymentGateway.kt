package com.resilientpos.payment.internal.domain

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId

/**
 * 결제 provider에 승인을 요청하는 port. `PaymentAttemptRepository`와 같은 자리에 둔다 —
 * 둘 다 aggregate가 바깥에 말을 거는 통로이고 구현은 `internal/adapter` 아래에 있다.
 *
 * provider가 쓰는 상태 이름을 이 경계 밖으로 내보내지 않는다. 어댑터가 자기 어휘를
 * [AuthorizationOutcome]의 세 갈래로 접고, 그 셋이 `PaymentAttemptStatus`의 결말 셋과 일대일이다.
 */
interface PaymentGateway {

    /**
     * 승인을 요청한다. 같은 [AuthorizationRequest.paymentId]로 다시 부르면 provider가 같은 답을
     * 주어야 한다 — 이 포트를 구현하는 쪽이 지킬 계약이다. 그 키로 별도 컬럼을 두지 않는 이유는
     * 한 시도가 provider 요청 1회이고, 그래서 시도의 식별자가 곧 그 요청의 식별자이기 때문이다.
     *
     * 예외를 던지지 않고 [AuthorizationOutcome.Unknown]을 돌려주는 것이 이 포트의 계약이다.
     * 전송이 끊기거나 시간이 다한 것은 "거절"이 아니라 "결과를 모른다"이기 때문이다.
     */
    fun authorize(request: AuthorizationRequest): AuthorizationOutcome
}

/**
 * provider에 보내는 승인 요청. aggregate가 가진 값만 담는다.
 */
data class AuthorizationRequest(
    val paymentId: PaymentId,
    val amount: Money,
)

/**
 * provider가 말한 결말. 셋뿐이고 각각이 `PaymentAttemptStatus`의 한 값이 된다.
 */
sealed interface AuthorizationOutcome {

    /** provider가 승인했다. 참조는 그쪽 원장을 가리키므로 반드시 있다. */
    data class Approved(val providerReference: String) : AuthorizationOutcome

    /** provider가 거절했다. 결과를 받았다는 점에서 [Unknown]과 다르다. 참조를 주지 않는 provider도 있다. */
    data class Declined(val providerReference: String?) : AuthorizationOutcome

    /** 결과를 확인하지 못했다. 실패가 아니라 미확인이다. */
    data object Unknown : AuthorizationOutcome
}
