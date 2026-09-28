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
         * 금액을 담는 컬럼(`t_order_line.unit_price`, `t_order_checkout.amount`, `t_payment_attempt.amount`)이 `numeric(19,4)`라서
         * 정수부는 19 - 4 = 15자리까지다. 이 상한이 없으면 도메인이 저장할 수 없는 금액을 만들어,
         * 주문이 커밋된 뒤 합계를 저장하는 시점에야 실패해 복구할 수 없는 상태가 된다.
         * 컬럼 타입을 바꾸는 PR은 이 상수도 함께 본다.
         */
        const val MAX_INTEGER_DIGITS = 15

        /**
         * 통화 자릿수를 넘는 소수 자리는 값이 0일 때만 살아남는데, 그 0의 개수에 두는 상한이다.
         * 상한이 없으면 `1.` 뒤에 0을 십만 개 붙인 입력이 자릿수 판정을 정당하게 통과한 뒤
         * 0을 떼는 데만 수십 ms를 쓴다. 떼는 비용이 적힌 자릿수의 제곱이기 때문이다.
         * `numeric(19,4)`에 담기는 어떤 금액도 20자리 소수를 필요로 하지 않는다.
         */
        const val MAX_TRAILING_ZEROS = 20

        /** `numeric(19,4)`의 소수부. 이보다 잘게 쪼개는 통화는 금액을 잃지 않고 저장할 수 없다. */
        const val MAX_FRACTION_DIGITS = 4
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
            // 0은 자릿수를 따질 것이 없다. 아래 두 가드는 0에서 뜻을 잃으므로(`0E+10`의 정수부가 11자리로 읽힌다)
            // 여기서 먼저 걷어낸다.
            if (amount.signum() == 0) {
                return Money(BigDecimal.ZERO.setScale(digits), currency)
            }
            if (amount.integerDigits() > MAX_INTEGER_DIGITS) {
                throw DomainException(INVALID_MONEY_AMOUNT, "저장할 수 있는 금액 범위를 넘습니다")
            }
            if (amount.scale().toLong() - digits > MAX_TRAILING_ZEROS) {
                throw DomainException(INVALID_MONEY_SCALE, "통화 표준 자릿수를 넘는 금액입니다")
            }
            // 두 가드를 지나야 편다. 뒤따르는 0을 떼어야 `1000.0000`처럼 scale만 크고 값은 canonical인 입력이
            // 살아남는데, 떼는 비용은 적힌 자릿수의 제곱이라 상한 없이 먼저 부르면 입력 크기가 그대로 비용이 된다.
            val minimal = amount.stripTrailingZeros()
            if (minimal.scale() > digits) {
                throw DomainException(INVALID_MONEY_SCALE, "통화 표준 자릿수를 넘는 금액입니다")
            }
            // 여기까지 온 값은 scale이 통화 자릿수 이하라 scale을 늘리는 변환만 남는다.
            // 0을 붙이는 일이라 값이 커지지 않고 UNNECESSARY로도 실패하지 않는다.
            return Money(minimal.setScale(digits, RoundingMode.UNNECESSARY), currency)
        }

        fun of(raw: String, currency: Currency): Money {
            val parsed = try {
                BigDecimal(raw)
            } catch (_: NumberFormatException) {
                throw DomainException(INVALID_MONEY_FORMAT, "금액으로 읽을 수 없는 문자열입니다")
            }
            return of(parsed, currency)
        }

        /**
         * 정수부 자릿수. 문자열로 만든 `BigDecimal`은 파싱할 때 `precision`을 채워 두므로 값을 펴지 않고 읽는다
         * (연산으로 만들어진 값에서는 처음 읽을 때 계산한다).
         * `Long`으로 세는 이유는 `scale`이 `Int.MIN_VALUE`까지 가기 때문이다. `1E+2147483647`은 13자라
         * 어떤 길이 제한도 지나가는데, `precision - scale`을 `Int`로 계산하면 `Int.MIN_VALUE`로 넘쳐
         * 상한 검사를 통과해 버린다. 그다음 `setScale`이 `ArithmeticException`을 던져 도메인 밖으로 샌다.
         */
        private fun BigDecimal.integerDigits(): Long = precision().toLong() - scale().toLong()

        /**
         * `Currency`는 금액 개념이 없는 코드(`XAU`, `XDR`, `XXX`)에 `-1`을 돌려준다. 그 값을 canonical scale로
         * 쓰면 금액이 10 단위로 반올림되므로 [Money]의 불변식을 세울 수 없다. 값을 만들지 않고 거절한다.
         * 위쪽도 막는다. 자릿수는 `java.util.currency.properties`로 런타임에 9까지 올릴 수 있는데,
         * 저장 컬럼의 소수부가 4라 그보다 잘게 쪼개는 통화는 금액을 잃는다.
         */
        private fun fractionDigitsOf(currency: Currency): Int {
            val digits = currency.defaultFractionDigits
            if (digits < 0 || digits > MAX_FRACTION_DIGITS) {
                throw DomainException(UNSUPPORTED_CURRENCY, "금액을 표현할 수 없는 통화입니다")
            }
            return digits
        }
    }
}

/**
 * 금액이 0인지, 음수인지. 멤버로 두면 `isZero()` getter가 생겨 Jackson이 금액 JSON에 필드를 더한다.
 * 이벤트 `serialized_event`와 API 응답의 모양이 바뀌므로 확장 프로퍼티로 둔다.
 */
val Money.isZero: Boolean get() = amount.signum() == 0

val Money.isNegative: Boolean get() = amount.signum() < 0
