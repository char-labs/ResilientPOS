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
        if (amount.scale() != currency.defaultFractionDigits) {
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

        fun zero(currency: Currency): Money = of("0", currency)

        fun of(raw: String, currency: Currency): Money {
            val canonical = try {
                BigDecimal(raw).setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                throw DomainException(INVALID_MONEY_SCALE, "통화 표준 자릿수를 넘는 금액입니다")
            }
            return Money(canonical, currency)
        }
    }
}
