package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuView

/**
 * 메뉴 조회·저장 응답 본문. `price`는 메뉴 통화 기준 decimal string이다(JS 숫자가 15자리 정수부와 소수를 정확히 담지 못한다).
 */
data class MenuResponse(
    val currency: String,
    val items: List<Item>,
) {
    data class Item(
        val code: String,
        val name: String,
        val price: String,
        val category: String,
    )

    companion object {
        fun from(view: MenuView) = MenuResponse(
            currency = view.currency.currencyCode,
            items = view.items.map { Item(it.code, it.name, it.price.amount.toPlainString(), it.category) },
        )
    }
}
