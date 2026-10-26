package com.resilientpos.catalog.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.isNegative
import com.resilientpos.store.StoreId
import java.util.Currency

/** 매장 메뉴 aggregate. 문서 하나를 통째로 검증해 만들고 불변 객체다. */
class Menu private constructor(
    val storeId: StoreId,
    val currency: Currency,
    val items: List<MenuItem>,
) {
    companion object {
        const val INVALID_MENU = "INVALID_MENU"

        const val MAX_ITEMS = 200
        const val MAX_CODE_LENGTH = 64
        const val MAX_NAME_LENGTH = 255
        const val MAX_CATEGORY_LENGTH = 50
        private const val NUL = '\u0000'

        /**
         * 통화는 항목 수와 무관하게 `Money.zero`로 검증한다(지원하지 않는 통화는 `Money`의 code).
         * 글자 수는 code point로 세고 공백은 `isBlank()`(NBSP·U+3000 포함), NUL은 포함 여부로 본다. 주문 line과 같은 판정이다.
         */
        fun create(storeId: StoreId, currency: Currency, items: List<MenuItem>): Menu {
            Money.zero(currency)
            if (items.size > MAX_ITEMS) {
                throw invalid("항목은 ${MAX_ITEMS}개를 넘을 수 없습니다")
            }
            val codes = HashSet<String>()
            items.forEachIndexed { index, item ->
                val n = index + 1
                requireText(item.code, "코드", MAX_CODE_LENGTH, n)
                requireText(item.name, "이름", MAX_NAME_LENGTH, n)
                requireText(item.category, "분류", MAX_CATEGORY_LENGTH, n)
                if (item.price.currency != currency) {
                    throw invalid("${n}번째 항목의 가격 통화가 메뉴 통화와 다릅니다")
                }
                if (item.price.isNegative) {
                    throw invalid("${n}번째 항목의 가격은 음수일 수 없습니다")
                }
                if (!codes.add(item.code)) {
                    throw invalid("${n}번째 항목의 코드가 앞 항목과 겹칩니다")
                }
            }
            return Menu(storeId, currency, items.toList())
        }

        private fun requireText(value: String, label: String, max: Int, n: Int) {
            if (value.isBlank()) throw invalid("${n}번째 항목의 ${label}는 비어 있을 수 없습니다")
            // NUL은 값이 없다는 null이 아니라 문자열 안의 U+0000 글자다. PostgreSQL text가 받지 못한다.
            if (value.contains(NUL)) throw invalid("${n}번째 항목의 ${label}에 쓸 수 없는 문자가 있습니다")
            if (value.codePointCount(0, value.length) > max) {
                throw invalid("${n}번째 항목의 ${label}는 ${max}자를 넘을 수 없습니다")
            }
        }

        private fun invalid(message: String) = DomainException(INVALID_MENU, message, DomainErrorKind.INVALID)
    }
}
