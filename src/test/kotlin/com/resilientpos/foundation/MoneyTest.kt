package com.resilientpos.foundation

import java.math.BigDecimal
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    private val krw = Currency.getInstance("KRW")
    private val usd = Currency.getInstance("USD")

    @Test
    fun `통화의 표준 자릿수를 canonical scale로 쓴다`() {
        assertEquals(BigDecimal("1000"), Money.of("1000", krw).amount)
        assertEquals(BigDecimal("10.50"), Money.of("10.5", usd).amount)
    }

    @Test
    fun `통화 자릿수를 넘는 소수 입력을 거절한다`() {
        val failure = assertFailsWith<DomainException> { Money.of("1000.5", krw) }

        assertEquals("INVALID_MONEY_SCALE", failure.code)
        assertTrue(failure.message.isNotBlank())
        assertTrue(failure.message != failure.code)
    }

    @Test
    fun `통화가 다른 금액은 더하지 않는다`() {
        val failure = assertFailsWith<DomainException> { Money.of("1000", krw) + Money.of("10.00", usd) }

        assertEquals("CURRENCY_MISMATCH", failure.code)
        assertTrue(failure.message.isNotBlank())
        assertTrue(failure.message != failure.code)
    }

    @Test
    fun `통화가 다른 금액은 비교하지 않는다`() {
        val failure = assertFailsWith<DomainException> {
            Money.of("1000", krw).compareTo(Money.of("10.00", usd))
        }

        assertEquals("CURRENCY_MISMATCH", failure.code)
    }

    @Test
    fun `덧셈과 뺄셈과 정수 배수가 scale을 유지하고 원본을 바꾸지 않는다`() {
        val price = Money.of("10.50", usd)
        val discount = Money.of("0.50", usd)

        assertEquals(Money.of("11.00", usd), price + Money.of("0.50", usd))
        assertEquals(Money.of("10.00", usd), price - discount)
        assertEquals(Money.of("31.50", usd), price * 3)
        assertEquals(Money.of("10.50", usd), price)
    }

    @Test
    fun `통화별 0 금액은 canonical scale을 가지고 덧셈의 항등원이다`() {
        assertEquals(Money.of("0", krw), Money.zero(krw))
        assertEquals(Money.of("0.00", usd), Money.zero(usd))
        assertEquals(Money.of("1000", krw), Money.zero(krw) + Money.of("1000", krw))
    }

    @Test
    fun `여러 금액을 0에서 시작해 합산한다`() {
        val lines = listOf(Money.of("10.50", usd), Money.of("0.25", usd), Money.of("4.25", usd))

        assertEquals(Money.of("15.00", usd), lines.fold(Money.zero(usd), Money::plus))
    }

    @Test
    fun `같은 통화 금액의 크기를 비교한다`() {
        assertEquals(1, Money.of("1001", krw).compareTo(Money.of("1000", krw)))
        assertEquals(0, Money.of("1000", krw).compareTo(Money.of("1000", krw)))
        assertTrue(Money.of("1000", krw) < Money.of("1001", krw))
        assertTrue(Money.of("1001", krw) > Money.of("1000", krw))
    }

    @Test
    fun `정수부가 15자리면 만들어지고 16자리면 INVALID_MONEY_AMOUNT로 거절한다`() {
        assertEquals("999999999999999", Money.of("999999999999999", krw).amount.toPlainString())
        assertEquals("999999999999999.99", Money.of("999999999999999.99", usd).amount.toPlainString())
        // 소수 4자리 통화는 정수부 15 + 소수 4 = 19자리로 numeric(19,4)의 한계와 정확히 같다.
        val clf = Currency.getInstance("CLF")
        assertEquals(4, clf.defaultFractionDigits)
        assertEquals("999999999999999.9999", Money.of("999999999999999.9999", clf).amount.toPlainString())
        assertFailsWith<DomainException> { Money.of("1000000000000000.0000", clf) }

        listOf("1000000000000000" to krw, "1000000000000000.00" to usd).forEach { (raw, currency) ->
            val failure = assertFailsWith<DomainException>(raw) { Money.of(raw, currency) }
            assertEquals(Money.INVALID_MONEY_AMOUNT, failure.code, raw)
            assertEquals(DomainErrorKind.INVALID, failure.kind, raw)
            assertTrue(failure.message.isNotBlank())
            assertTrue(failure.message != failure.code)
        }
    }

    @Test
    fun `연산 결과가 저장 범위를 넘으면 거절한다`() {
        val large = Money.of("999999999999999", krw)

        assertFailsWith<DomainException>("times") { large * 2 }
        assertFailsWith<DomainException>("plus") { large + Money.of("1", krw) }
        assertFailsWith<DomainException>("minus") { large - Money.of("-1", krw) }
        assertFailsWith<DomainException>("생성자") { Money(BigDecimal("1000000000000000"), krw) }
        assertFailsWith<DomainException>("copy") { large.copy(amount = BigDecimal("1000000000000000")) }
    }

    @Test
    fun `0과 음수는 자릿수만 보므로 영향받지 않는다`() {
        assertEquals(BigDecimal("0"), Money.zero(krw).amount)
        assertEquals(BigDecimal("0.00"), Money.zero(usd).amount)
        assertEquals(BigDecimal("-999999999999999"), Money.of("-999999999999999", krw).amount)
        assertFailsWith<DomainException> { Money.of("-1000000000000000", krw) }
    }
}
