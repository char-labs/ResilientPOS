package com.resilientpos.payment.internal.adapter.fake

import com.resilientpos.payment.AuthorizePayment
import com.resilientpos.payment.GatewayAuthorization
import com.resilientpos.payment.PaymentGateway
import org.springframework.stereotype.Component

/**
 * 로컬·테스트용 결제 provider. 외부 연동 전략이 P0로 두고 "항상 유지"라고 정한 구현이라
 * 실제 provider가 붙어도 지우지 않는다. 실제 provider를 고르기 전까지는 유일한 구현이다.
 *
 * 결정론적으로 승인만 돌려준다. timeout·거절·중복 callback 같은 결함 주입은 그 경로를 실제로 타는
 * 호출부가 생기는 PR이 더한다. 부르는 쪽 없이 시나리오 설정을 먼저 만들지 않는다.
 *
 * `providerReference`는 `merchantReference`에서 그대로 유도한다. 무작위 값을 쓰면 같은 요청을
 * 다시 보냈을 때 참조가 달라져서, 재전송을 중복 승인과 구별하지 못하게 된다.
 */
@Component
class FakePaymentGateway : PaymentGateway {

    override fun authorize(command: AuthorizePayment): GatewayAuthorization =
        GatewayAuthorization.Approved(
            providerReference = "$PROVIDER_REFERENCE_PREFIX${command.merchantReference}",
            approvedAmount = command.amount,
        )

    companion object {
        const val PROVIDER_REFERENCE_PREFIX = "fake-"
    }
}
