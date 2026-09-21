package com.resilientpos.foundation

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * 통화의 표준 소수 자릿수를 canonical scale로 유지하는 불변 금액이다.
 * 음수 허용 여부 같은 업무 불변식은 각 Aggregate가 판단한다.
 *
 * 통화가 다른 금액에는 순서가 없으므로 `Comparable`을 구현하지 않는다. `compareTo` 연산자 함수만 두어
 * `<`, `>` 연산자는 쓰되 `sorted()`처럼 전순서를 전제하는 표준 API에는 넘어가지 않게 한다.
 */
data class Money(val amount: BigDecimal, val currency: Currency) {

    init {
        if (amount.scale() != fractionDigitsOf(currency)) {
            throw DomainException(INVALID_MONEY_SCALE, "통화 표준 자릿수를 넘는 금액입니다")
        }
        if (amount.integerDigits() > MAX_INTEGER_DIGITS) {
            throw DomainException(INVALID_MONEY_AMOUNT, "저장할 수 있는 금액 범위를 넘습니다")
        }
    }

    operator fun plus(other: Money): Money = Money(amount + other.amountIn(currency), currency)

    operator fun minus(other: Money): Money = Money(amount - other.amountIn(currency), currency)

    operator fun times(multiplier: Int): Money = Money(amount * BigDecimal(multiplier), currency)

    operator fun compareTo(other: Money): Int = amount.compareTo(other.amountIn(currency))

    private fun amountIn(expected: Currency): BigDecimal {
        if (currency != expected) {
            throw DomainException(CURRENCY_MISMATCH, "통화가 다른 금액끼리는 연산할 수 없습니다")
        }
        return amount
    }

    companion object {
        const val INVALID_MONEY_SCALE = "INVALID_MONEY_SCALE"
        const val INVALID_MONEY_AMOUNT = "INVALID_MONEY_AMOUNT"

        /**
         * 금액을 담는 컬럼(`t_order_line.unit_price`, `t_order_checkout.amount`)이 `numeric(19,4)`라서
         * 정수부는 19 - 4 = 15자리까지다. 이 상한이 없으면 도메인이 저장할 수 없는 금액을 만들어,
         * 주문이 커밋된 뒤 합계를 저장하는 시점에야 실패해 복구할 수 없는 상태가 된다.
         * 컬럼 타입을 바꾸는 PR은 이 상수도 함께 본다.
         */
        const val MAX_INTEGER_DIGITS = 15
        const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
        const val UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY"
        const val INVALID_MONEY_FORMAT = "INVALID_MONEY_FORMAT"

        fun zero(currency: Currency): Money = of(BigDecimal.ZERO, currency)

        /**
         * 값을 펴기 전에 메타데이터로 먼저 판정한다. `precision()`과 `scale()`은 `BigDecimal`이 이미 들고 있는
         * 정수 두 개라 입력 크기와 무관하지만, `setScale`은 자릿수만큼의 `BigInteger`를 실제로 만든다.
         * 검사를 뒤에 두면 `1E+100000` 하나를 거절하는 데만 수백 ms가 든다.
         */
        fun of(amount: BigDecimal, currency: Currency): Money {
            val digits = fractionDigitsOf(currency)
            // 뒤따르는 0을 떼어야 유효 자릿수가 드러난다. `1000.0000`을 scale만 보고 거절하면 안 되기 때문이다.
            // 떼는 비용은 `precision()`, 즉 입력에 실제로 적힌 자릿수에 비례한다. 지수 표기는 한 자리라 공짜다.
            val minimal = amount.stripTrailingZeros()
            if (minimal.scale() > digits) {
                throw DomainException(INVALID_MONEY_SCALE, "통화 표준 자릿수를 넘는 금액입니다")
            }
            if (minimal.integerDigits() > MAX_INTEGER_DIGITS) {
                throw DomainException(INVALID_MONEY_AMOUNT, "저장할 수 있는 금액 범위를 넘습니다")
            }
            // 두 검사를 지난 값은 scale이 [정수부 상한의 음수, digits] 안이라 scale을 늘리는 변환만 남는다.
            // 0을 붙이는 일이라 값이 커지지 않고 UNNECESSARY로도 실패하지 않는다.
            return Money(minimal.setScale(digits, RoundingMode.UNNECESSARY), currency)
        }

        fun of(raw: String, currency: Currency): Money {
            val parsed = try {
                BigDecimal(raw)
            } catch (e: NumberFormatException) {
                throw DomainException(INVALID_MONEY_FORMAT, "금액으로 읽을 수 없는 문자열입니다")
            }
            return of(parsed, currency)
        }

        /**
         * 정수부 자릿수. `BigDecimal`의 메타데이터만 읽으므로 값을 문자열로 펴지 않는다.
         * `Long`으로 세는 이유는 `scale`이 `Int.MIN_VALUE`까지 가기 때문이다. `1E+2147483647`은 13자라
         * 어떤 길이 제한도 지나가는데, `precision - scale`을 `Int`로 계산하면 `Int.MIN_VALUE`로 넘쳐
         * 상한 검사를 통과해 버린다. 그다음 `setScale`이 `ArithmeticException`을 던져 도메인 밖으로 샌다.
         */
        private fun BigDecimal.integerDigits(): Long = precision().toLong() - scale().toLong()

        /**
         * `Currency`는 금액 개념이 없는 코드(`XAU`, `XDR`, `XXX`)에 `-1`을 돌려준다. 그 값을 canonical scale로
         * 쓰면 금액이 10 단위로 반올림되므로 [Money]의 불변식을 세울 수 없다. 값을 만들지 않고 거절한다.
         */
        private fun fractionDigitsOf(currency: Currency): Int {
            val digits = currency.defaultFractionDigits
            if (digits < 0) {
                throw DomainException(UNSUPPORTED_CURRENCY, "금액을 표현할 수 없는 통화입니다")
            }
            return digits
        }
    }
}
