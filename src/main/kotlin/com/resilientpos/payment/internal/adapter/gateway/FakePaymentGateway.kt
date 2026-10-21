package com.resilientpos.payment.internal.adapter.gateway

import com.resilientpos.foundation.Money
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 실제 PG가 붙기 전까지 쓰는 구현. 네트워크를 타지 않고 금액만 보고 답한다.
 *
 * 금액으로 결말을 가르는 이유는 통합 테스트가 설정도 스텁 교체도 없이 일곱 경로(승인·거절·조회로 승인·조회로 거절·계속 불명·조회로 다른 금액 승인·승인 요청부터 다른 금액 승인)를 다 밟게 하려는 것이다.
 * 같은 입력이 항상 같은 결과라 재현된다.
 *
 * `latency`는 부하 측정(`load-test/`)에서 느린 provider를 흉내 내는 지연이고 기본값은 0이다. 지연이 `deadline` 이상이면 deadline에서
 * 결과를 모른다고 답한다. 실제 PG adapter에서는 HTTP client의 timeout이 deadline 자리다.
 *
 * 보는 값은 **최소 단위의 끝자리**다. `BigDecimal.unscaledValue()`가 그것을 바로 준다 —
 * `Money`가 통화의 canonical scale로 정규화하므로 KRW `4500`과 USD `45.00`이 둘 다 `4500`이 된다.
 */
@Component
class FakePaymentGateway(
    @Value("\${resilientpos.payment.fake-gateway.latency}") private val latency: Duration = Duration.ZERO,
    @Value("\${resilientpos.payment.fake-gateway.deadline}") private val deadline: Duration = Duration.ofSeconds(3),
) : PaymentGateway {

    override fun authorize(request: AuthorizationRequest): AuthorizationOutcome = respond { answer(request) }

    /** 승인 요청이 결과를 모른 채 끝난 뒤 provider에 다시 묻는 경로다. 끝자리가 갈래를 정하고 나머지는 승인 요청과 같은 답이다. */
    override fun query(request: AuthorizationRequest): AuthorizationOutcome = respond {
        when (lastMinorDigit(request)) {
            UNKNOWN_THEN_APPROVED_DIGIT -> AuthorizationOutcome.Approved(reference(request), request.amount)
            UNKNOWN_THEN_DECLINED_DIGIT -> AuthorizationOutcome.Declined(reference(request))
            STAYS_UNKNOWN_DIGIT -> AuthorizationOutcome.Unknown
            UNKNOWN_THEN_OVERSTATED_DIGIT -> AuthorizationOutcome.Approved(reference(request), oneMinorUnitMore(request))
            else -> answer(request)
        }
    }

    /** 실제 provider처럼 호출 스레드에서 기다린다. deadline까지 답이 없으면 승인됐는지 모른다(timeout은 실패 확정이 아니다). */
    private fun respond(answer: () -> AuthorizationOutcome): AuthorizationOutcome {
        if (latency >= deadline) {
            Thread.sleep(deadline)
            return AuthorizationOutcome.Unknown
        }
        Thread.sleep(latency)
        return answer()
    }

    private fun answer(request: AuthorizationRequest): AuthorizationOutcome =
        when (lastMinorDigit(request)) {
            DECLINE_DIGIT -> AuthorizationOutcome.Declined(reference(request))
            // 결과를 확인하지 못한 상황을 흉내 낸다. 실제로 기다리지는 않는다 — 테스트를 느리게 할 뿐이다.
            UNKNOWN_THEN_APPROVED_DIGIT, UNKNOWN_THEN_DECLINED_DIGIT, STAYS_UNKNOWN_DIGIT, UNKNOWN_THEN_OVERSTATED_DIGIT ->
                AuthorizationOutcome.Unknown
            OVERSTATED_DIGIT -> AuthorizationOutcome.Approved(reference(request), oneMinorUnitMore(request))
            else -> AuthorizationOutcome.Approved(reference(request), request.amount)
        }

    /** provider가 시도보다 최소 단위 하나 많게 승인했다고 말하는 경우. 통화마다 최소 단위가 달라 scale로 만든다. */
    private fun oneMinorUnitMore(request: AuthorizationRequest): Money {
        val money = request.amount
        return Money(money.amount.add(BigDecimal.ONE.movePointLeft(money.amount.scale())), money.currency)
    }

    private fun lastMinorDigit(request: AuthorizationRequest) =
        request.amount.amount.unscaledValue().mod(BigInteger.TEN).toInt()

    private fun reference(request: AuthorizationRequest) = "$REFERENCE_PREFIX${request.paymentId.value}"

    companion object {
        private const val DECLINE_DIGIT = 1
        private const val UNKNOWN_THEN_APPROVED_DIGIT = 2
        private const val UNKNOWN_THEN_DECLINED_DIGIT = 3
        private const val STAYS_UNKNOWN_DIGIT = 4
        private const val UNKNOWN_THEN_OVERSTATED_DIGIT = 5
        private const val OVERSTATED_DIGIT = 6

        /** 이 참조가 실제 provider 원장을 가리키지 않는다는 것이 이름에 드러나야 한다. */
        private const val REFERENCE_PREFIX = "FAKE-"
    }
}
