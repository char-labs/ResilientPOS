package com.resilientpos.foundation

import java.math.BigDecimal
import java.util.Currency
import kotlin.system.measureTimeMillis
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

    @Test
    fun `지수 표기 금액을 값을 펴지 않고 거절한다`() {
        val huge = BigDecimal("1E+100000")

        val failure = assertFailsWith<DomainException> { Money.of(huge, krw) }
        assertEquals("INVALID_MONEY_AMOUNT", failure.code)

        // 거절 비용이 입력의 크기를 따라가면 안 된다. 펴면 한 번에 수백 ms가 든다.
        val elapsed = measureTimeMillis {
            repeat(REPEAT_COUNT) { assertFailsWith<DomainException> { Money.of(huge, krw) } }
        }
        assertTrue(elapsed < REJECTION_BUDGET_MILLIS, "${REPEAT_COUNT}회 거절에 ${elapsed}ms가 들었다")
    }

    @Test
    fun `지수 표기를 쓴 소수도 값을 펴지 않고 거절한다`() {
        val tiny = BigDecimal("1E-100000")

        val elapsed = measureTimeMillis {
            repeat(REPEAT_COUNT) {
                assertEquals("INVALID_MONEY_SCALE", assertFailsWith<DomainException> { Money.of(tiny, krw) }.code)
            }
        }
        assertTrue(elapsed < REJECTION_BUDGET_MILLIS, "${REPEAT_COUNT}회 거절에 ${elapsed}ms가 들었다")
    }

    @Test
    fun `소수 자릿수가 없는 통화로는 금액을 만들지 않는다`() {
        // XAU·XDR·XXX는 defaultFractionDigits가 -1이다. canonical scale로 쓰면 금액이 10 단위로 반올림된다.
        for (code in listOf("XAU", "XDR", "XXX")) {
            val currency = Currency.getInstance(code)
            val failure = assertFailsWith<DomainException>(code) { Money.of("100", currency) }

            assertEquals("UNSUPPORTED_CURRENCY", failure.code, code)
            assertTrue(failure.message.isNotBlank())
            assertTrue(failure.message != failure.code)
        }
    }

    @Test
    fun `소수 자릿수가 없는 통화는 생성자와 zero로도 우회할 수 없다`() {
        val xau = Currency.getInstance("XAU")

        assertFailsWith<DomainException>("생성자") { Money(BigDecimal("1.0E+2"), xau) }
        assertFailsWith<DomainException>("zero") { Money.zero(xau) }
    }

    @Test
    fun `숫자가 아닌 금액 문자열은 도메인 예외다`() {
        for (raw in listOf("abc", "", " ", "1,000", "1.0.0")) {
            val failure = assertFailsWith<DomainException>(raw) { Money.of(raw, krw) }

            assertEquals("INVALID_MONEY_FORMAT", failure.code, raw)
            assertTrue(failure.message.isNotBlank())
            assertTrue(failure.message != failure.code)
        }
    }

    @Test
    fun `BigDecimal을 그대로 받는 입구도 문자열 입구와 같은 값을 만든다`() {
        assertEquals(Money.of("1000", krw), Money.of(BigDecimal("1000"), krw))
        assertEquals(Money.of("10.5", usd), Money.of(BigDecimal("10.5"), usd))
        assertEquals(BigDecimal("100"), Money.of(BigDecimal("1E+2"), krw).amount)
    }

    private companion object {
        const val REPEAT_COUNT = 1_000

        /** 펴지 않으면 1000회가 수 ms다. 머신 편차를 넉넉히 덮고도 375ms짜리 한 번보다 작은 상한을 고른다. */
        const val REJECTION_BUDGET_MILLIS = 1_000L
    }
}
