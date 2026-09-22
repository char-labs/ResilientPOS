package com.resilientpos.payment.internal.adapter.fake

import com.resilientpos.foundation.Money
import com.resilientpos.payment.AuthorizePayment
import com.resilientpos.payment.GatewayAuthorization
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class FakePaymentGatewayTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val gateway = FakePaymentGateway()

    private fun command(reference: String = "order-1", amount: String = "4500") =
        AuthorizePayment(merchantReference = reference, idempotencyKey = UUID.randomUUID().toString(), amount = Money.of(amount, krw))

    @Test
    fun `승인 금액은 요청 금액과 같다`() {
        val approved = gateway.authorize(command()) as GatewayAuthorization.Approved

        assertEquals(Money.of("4500", krw), approved.approvedAmount)
    }

    @Test
    fun `같은 merchantReference는 언제나 같은 provider 참조를 받는다`() {
        // 재전송을 중복 승인과 구별하려면 참조가 요청에서 결정돼야 한다.
        // idempotencyKey가 달라도 같은 시도를 가리키는 요청이면 참조가 같아야 한다.
        val first = gateway.authorize(command()) as GatewayAuthorization.Approved
        val second = gateway.authorize(command()) as GatewayAuthorization.Approved

        assertEquals(first.providerReference, second.providerReference)
        assertEquals("${FakePaymentGateway.PROVIDER_REFERENCE_PREFIX}order-1", first.providerReference)
    }

    @Test
    fun `merchantReference가 다르면 참조도 다르다`() {
        val first = gateway.authorize(command(reference = "order-1")) as GatewayAuthorization.Approved
        val second = gateway.authorize(command(reference = "order-2")) as GatewayAuthorization.Approved

        assertEquals(false, first.providerReference == second.providerReference)
    }
}
