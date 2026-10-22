package com.resilientpos.payment.internal.adapter.gateway

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import java.time.Duration
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 통합 테스트가 이 규칙으로 경로를 고르므로, 규칙이 바뀌면 그 테스트들이 조용히 다른 경로를 밟는다.
 */
class FakePaymentGatewayTest {

    private val gateway = FakePaymentGateway()
    private val krw: Currency = Currency.getInstance("KRW")
    private val usd: Currency = Currency.getInstance("USD")
    private val paymentId = PaymentId(UUID.fromString("00000000-0000-0000-0000-00000000beef"))

    private fun authorize(amount: String, currency: Currency = krw) =
        gateway.authorize(AuthorizationRequest(paymentId, Money.of(amount, currency)))

    private fun query(amount: String, currency: Currency = krw) =
        gateway.query(AuthorizationRequest(paymentId, Money.of(amount, currency)))

    @Test
    fun `끝자리가 1이면 거절한다`() {
        val outcome = assertIs<AuthorizationOutcome.Declined>(authorize("4501"))

        assertEquals("FAKE-${paymentId.value}", outcome.providerReference)
    }

    @Test
    fun `끝자리가 2이면 결과를 모른다`() {
        assertIs<AuthorizationOutcome.Unknown>(authorize("4502"))
    }

    @Test
    fun `끝자리가 3이나 4여도 승인 요청은 결과를 모른다`() {
        assertIs<AuthorizationOutcome.Unknown>(authorize("4503"))
        assertIs<AuthorizationOutcome.Unknown>(authorize("4504"))
    }

    @Test
    fun `끝자리가 2인 요청을 조회하면 승인이다`() {
        val outcome = assertIs<AuthorizationOutcome.Approved>(query("4502"))

        assertEquals("FAKE-${paymentId.value}", outcome.providerReference)
    }

    @Test
    fun `끝자리가 3인 요청을 조회하면 거절이다`() {
        val outcome = assertIs<AuthorizationOutcome.Declined>(query("4503"))

        assertEquals("FAKE-${paymentId.value}", outcome.providerReference)
    }

    @Test
    fun `끝자리가 4인 요청을 조회하면 여전히 결과를 모른다`() {
        assertIs<AuthorizationOutcome.Unknown>(query("4504"))
    }

    @Test
    fun `끝자리가 2·3·4가 아닌 요청을 조회하면 승인 요청과 같은 답이다`() {
        assertEquals(authorize("4501"), query("4501"))
        assertEquals(authorize("4500"), query("4500"))
    }

    @Test
    fun `그 밖의 끝자리는 승인한다`() {
        val outcome = assertIs<AuthorizationOutcome.Approved>(authorize("4500"))

        assertEquals("FAKE-${paymentId.value}", outcome.providerReference)
    }

    @Test
    fun `소수 통화도 최소 단위의 끝자리로 가른다`() {
        // USD 45.01은 최소 단위 4501이라 KRW 4501과 같은 경로다. 소수점 자리가 아니라 최소 단위를 본다.
        assertIs<AuthorizationOutcome.Declined>(authorize("45.01", usd))
        assertIs<AuthorizationOutcome.Approved>(authorize("45.00", usd))
    }

    @Test
    fun `승인에는 시도와 같은 금액이 실린다`() {
        assertEquals(Money.of("4500", krw), assertIs<AuthorizationOutcome.Approved>(authorize("4500")).amount)
        assertEquals(Money.of("4502", krw), assertIs<AuthorizationOutcome.Approved>(query("4502")).amount)
    }

    @Test
    fun `끝자리가 5이면 승인 요청은 결과를 모르고 조회는 1단위 많은 금액으로 승인한다`() {
        assertIs<AuthorizationOutcome.Unknown>(authorize("4505"))

        val outcome = assertIs<AuthorizationOutcome.Approved>(query("4505"))
        assertEquals(Money.of("4506", krw), outcome.amount)
        assertEquals("FAKE-${paymentId.value}", outcome.providerReference)
    }

    @Test
    fun `끝자리가 6이면 승인 요청도 조회도 1단위 많은 금액으로 승인한다`() {
        assertEquals(Money.of("4607", krw), assertIs<AuthorizationOutcome.Approved>(authorize("4606")).amount)
        assertEquals(Money.of("4607", krw), assertIs<AuthorizationOutcome.Approved>(query("4606")).amount)
    }

    @Test
    fun `1단위 많음은 그 통화의 최소 단위 하나다`() {
        assertEquals(Money.of("45.07", usd), assertIs<AuthorizationOutcome.Approved>(authorize("45.06", usd)).amount)
        assertEquals(Money.of("45.06", usd), assertIs<AuthorizationOutcome.Approved>(query("45.05", usd)).amount)
    }

    @Test
    fun `지연을 주면 승인과 조회가 그만큼 늦게 답한다`() {
        val slow = FakePaymentGateway(Duration.ofMillis(200))
        val request = AuthorizationRequest(paymentId, Money.of("4500", krw))

        val authorizing = measureTime { slow.authorize(request) }
        val querying = measureTime { slow.query(request) }

        assertTrue(authorizing.inWholeMilliseconds >= 200, "승인이 ${authorizing}만에 답했다")
        assertTrue(querying.inWholeMilliseconds >= 200, "조회가 ${querying}만에 답했다")
    }

    @Test
    fun `지연이 deadline 이상이면 deadline에서 결과를 모른다고 답한다`() {
        val timingOut = FakePaymentGateway(latency = Duration.ofMillis(800), deadline = Duration.ofMillis(200))
        val request = AuthorizationRequest(paymentId, Money.of("4500", krw))

        lateinit var authorized: AuthorizationOutcome
        lateinit var queried: AuthorizationOutcome
        val authorizing = measureTime { authorized = timingOut.authorize(request) }
        val querying = measureTime { queried = timingOut.query(request) }

        assertIs<AuthorizationOutcome.Unknown>(authorized)
        assertIs<AuthorizationOutcome.Unknown>(queried)
        assertTrue(authorizing.inWholeMilliseconds in 200..<800, "승인이 ${authorizing}만에 답했다")
        assertTrue(querying.inWholeMilliseconds in 200..<800, "조회가 ${querying}만에 답했다")
    }

    @Test
    fun `지연이 음수이거나 deadline이 양수가 아니면 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> { FakePaymentGateway(latency = Duration.ofMillis(-1)) }
        assertFailsWith<IllegalArgumentException> { FakePaymentGateway(deadline = Duration.ZERO) }
    }
}
