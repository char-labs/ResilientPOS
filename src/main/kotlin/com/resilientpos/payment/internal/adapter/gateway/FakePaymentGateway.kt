package com.resilientpos.payment.internal.adapter.gateway

import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.math.BigInteger
import org.springframework.stereotype.Component

/**
 * 실제 PG가 붙기 전까지 쓰는 구현. 네트워크를 타지 않고 금액만 보고 답한다.
 *
 * 금액으로 결말을 가르는 이유는 통합 테스트가 설정도 스텁 교체도 없이 세 경로를 다 밟게 하려는 것이다.
 * 같은 입력이 항상 같은 결과라 재현된다.
 *
 * 보는 값은 **최소 단위의 끝자리**다. `BigDecimal.unscaledValue()`가 그것을 바로 준다 —
 * `Money`가 통화의 canonical scale로 정규화하므로 KRW `4500`과 USD `45.00`이 둘 다 `4500`이 된다.
 */
@Component
class FakePaymentGateway : PaymentGateway {

    override fun authorize(request: AuthorizationRequest): AuthorizationOutcome {
        val lastMinorDigit = request.amount.amount.unscaledValue().mod(BigInteger.TEN).toInt()
        return when (lastMinorDigit) {
            DECLINE_DIGIT -> AuthorizationOutcome.Declined("$REFERENCE_PREFIX${request.paymentId.value}")
            // 결과를 확인하지 못한 상황을 흉내 낸다. 실제로 기다리지는 않는다 — 테스트를 느리게 할 뿐이다.
            UNKNOWN_DIGIT -> AuthorizationOutcome.Unknown
            else -> AuthorizationOutcome.Approved("$REFERENCE_PREFIX${request.paymentId.value}")
        }
    }

    companion object {
        const val DECLINE_DIGIT = 1
        const val UNKNOWN_DIGIT = 2

        /** 이 참조가 실제 provider 원장을 가리키지 않는다는 것이 이름에 드러나야 한다. */
        const val REFERENCE_PREFIX = "FAKE-"
    }
}
