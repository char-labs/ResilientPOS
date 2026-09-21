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
        // 정수부 자릿수만 센다. BigDecimal의 메타데이터만 읽으므로 값을 문자열로 펴지 않는다.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
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
            // 뒤따르는 0을 떼어야 유효 자릿수가 드러난다. 떼는 비용은 정수부 자릿수에 비례하고 값을 펴지 않는다.
            val minimal = amount.stripTrailingZeros()
            if (minimal.scale() > digits) {
                throw DomainException(INVALID_MONEY_SCALE, "통화 표준 자릿수를 넘는 금액입니다")
            }
            if (minimal.precision() - minimal.scale() > MAX_INTEGER_DIGITS) {
                throw DomainException(INVALID_MONEY_AMOUNT, "저장할 수 있는 금액 범위를 넘습니다")
            }
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
