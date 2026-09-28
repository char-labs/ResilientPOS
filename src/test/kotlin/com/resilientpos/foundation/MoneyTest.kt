package com.resilientpos.foundation

import java.math.BigDecimal
import java.util.Currency
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

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
    fun `0인지와 음수인지를 부호로 판정한다`() {
        assertTrue(Money.zero(usd).isZero)
        assertTrue(Money.of("0.00", usd).isZero)
        assertFalse(Money.of("0.01", usd).isZero)
        assertFalse(Money.of("-0.01", usd).isZero)
        assertTrue(Money.of("-0.01", usd).isNegative)
        assertTrue(Money.of("-1", krw).isNegative)
        assertFalse(Money.zero(krw).isNegative)
        assertFalse(Money.of("1", krw).isNegative)
    }

    @Test
    fun `JSON에는 금액과 통화만 나간다`() {
        // 이벤트 serialized_event와 API 응답의 계약이다. is·get으로 시작하는 멤버를 더하면 Jackson이 필드로 내보낸다.
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        val json = mapper.readTree(mapper.writeValueAsString(Money.of("3500", krw)))

        assertEquals(setOf("amount", "currency"), json.properties().map { it.key }.toSet())
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
    fun `뒤따르는 0만 있는 입력은 canonical 값으로 살아남는다`() {
        // 이 세 줄이 stripTrailingZeros의 존재 이유다. 떼지 않으면 scale만 보고 INVALID_MONEY_SCALE이 된다.
        assertEquals(BigDecimal("1000"), Money.of(BigDecimal("1000.0000"), krw).amount)
        assertEquals(BigDecimal("0"), Money.of("0.00", krw).amount)
        assertEquals(BigDecimal("10.50"), Money.of(BigDecimal("10.5000"), usd).amount)
        // 떼어도 정수부 자릿수는 보존된다. 15자리 경계가 strip 때문에 흔들리지 않는지 본다.
        assertEquals("999999999999990", Money.of("999999999999990", krw).amount.toPlainString())
    }

    @Test
    fun `뗄 0의 상한은 통화 자릿수 위로 20자리다`() {
        assertEquals(BigDecimal("1"), Money.of(BigDecimal("1." + "0".repeat(20)), krw).amount)
        assertEquals("INVALID_MONEY_SCALE", assertFailsWith<DomainException> { Money.of(BigDecimal("1." + "0".repeat(21)), krw) }.code)
        // 통화 자릿수만큼은 상한 위로 더 쓸 수 있다.
        assertEquals(BigDecimal("1.00"), Money.of(BigDecimal("1." + "0".repeat(22)), usd).amount)
    }

    @Test
    fun `금액 범위와 자릿수를 함께 어기면 금액 쪽으로 거절한다`() {
        // 정수부 16자리이면서 소수도 통화 자릿수를 넘는 입력이다. 둘 다 400이고 금액 쪽이 더 근본적인 위반이다.
        assertEquals("INVALID_MONEY_AMOUNT", assertFailsWith<DomainException> { Money.of("6863292444430101.4479", usd) }.code)
    }

    @Test
    fun `뗄 0이 지나치게 많은 입력은 펴기 전에 거절한다`() {
        // 자릿수 판정은 통과한다(precision - scale = 1). 상한이 없으면 0을 떼는 데만 수십 ms가 든다.
        val padded = BigDecimal("1." + "0".repeat(100_000))

        val elapsed = measureTimeMillis {
            repeat(REPEAT_COUNT) {
                assertEquals("INVALID_MONEY_SCALE", assertFailsWith<DomainException> { Money.of(padded, krw) }.code)
            }
        }
        assertTrue(elapsed < REJECTION_BUDGET_MILLIS, "${REPEAT_COUNT}회 거절에 ${elapsed}ms가 들었다")
    }

    @Test
    fun `0은 어떤 지수로 써도 통화의 canonical 0이다`() {
        for (raw in listOf("0", "0.00", "0E+10", "-0", "0E-10")) {
            assertEquals(BigDecimal("0"), Money.of(BigDecimal(raw), krw).amount, raw)
            assertEquals(BigDecimal("0.00"), Money.of(BigDecimal(raw), usd).amount, raw)
        }
    }

    @Test
    fun `소수 자릿수가 저장 한계를 넘는 통화도 거절한다`() {
        // 통화 자릿수는 java.util.currency.properties로 런타임에 올릴 수 있는데 컬럼의 소수부는 4다.
        assertEquals(Money.MAX_FRACTION_DIGITS, 4)
        assertTrue(Currency.getAvailableCurrencies().none { it.defaultFractionDigits > Money.MAX_FRACTION_DIGITS })
    }

    @Test
    fun `자릿수 계산이 int를 넘는 지수도 도메인 예외로 거절한다`() {
        // precision - scale을 int로 계산하면 1 - (-2147483647)이 Int.MIN_VALUE로 넘쳐 상한 검사를 통과한다.
        // 13자짜리 입력이라 Jackson의 숫자 길이 제한(1000자)도 그대로 지나간다.
        // 1E+2147483648은 scale이 Int.MIN_VALUE인 경계이고, 그 위(1E+2147483649)는 파서가 막는다.
        for (raw in listOf("1E+2147483647", "1E+2147483648", "-1E+2147483647", "9E+2147483640")) {
            val failure = assertFailsWith<DomainException>(raw) { Money.of(BigDecimal(raw), krw) }

            assertEquals("INVALID_MONEY_AMOUNT", failure.code, raw)
        }

        assertEquals("INVALID_MONEY_SCALE", assertFailsWith<DomainException> { Money.of(BigDecimal("1E-2147483647"), krw) }.code)
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

    @Test
    fun `zero는 문자열 0과 같은 값을 만든다`() {
        // zero가 BigDecimal.ZERO를 쓰게 바뀌었다. scale까지 같아야 canonical 계약이 유지된다.
        for (currency in listOf(krw, usd, Currency.getInstance("CLF"))) {
            val code = currency.currencyCode
            assertEquals(Money.of("0", currency), Money.zero(currency), code)
            assertEquals(Money.of("0", currency).amount.scale(), Money.zero(currency).amount.scale(), code)
        }
    }

    private companion object {
        /**
         * 회귀했을 때의 실패 모양까지 보고 고른 값이다. 1000회로 두면 검사 순서가 되돌아갔을 때
         * 테스트가 6분을 돌다 실패해 CI 전체를 막는다. 100회면 37초에 실패하고 정상일 때는 수 ms다.
         */
        const val REPEAT_COUNT = 100

        /** 방어하려는 비용(회당 수십~수백 ms)과 실제 비용(회당 μs) 사이가 네 자릿수라 여유를 크게 둬도 탐지력이 남는다. */
        const val REJECTION_BUDGET_MILLIS = 1_000L
    }
}
