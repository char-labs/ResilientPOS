package com.resilientpos.catalog.internal.adapter.persistence

import com.resilientpos.catalog.internal.domain.Menu
import com.resilientpos.catalog.internal.domain.MenuItem
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import java.util.Currency
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * `t_menu.menu` 문서와 도메인 [Menu]의 변환. 필드 이름(`currency`, `items`, `code`, `name`, `price`, `category`)이 저장 형식이다.
 * 저장하는 것은 검증을 통과한 도메인 메뉴를 다시 직렬화한 문서이므로 요청의 모르는 필드는 남지 않는다.
 * `price`는 통화 자릿수의 decimal string이다. 나중에 더하는 필드는 기본값을 둬야 이미 저장된 문서를 읽을 수 있다.
 */
@Component
class MenuDocumentMapper(private val jsonMapper: JsonMapper) {

    fun toJson(menu: Menu): String = jsonMapper.writeValueAsString(
        Document(
            currency = menu.currency.currencyCode,
            items = menu.items.map { Document.Item(it.code, it.name, it.price.amount.toPlainString(), it.category) },
        ),
    )

    fun toMenu(storeId: StoreId, json: String): Menu {
        val document = jsonMapper.readValue(json, Document::class.java)
        val currency = Currency.getInstance(document.currency)
        return Menu.create(
            storeId,
            currency,
            document.items.map { MenuItem(it.code, it.name, Money.of(it.price, currency), it.category) },
        )
    }

    /** 두 문서가 JSON 값으로 같은지. 공백과 키 순서는 보지 않는다. */
    fun isSame(json: String, other: String): Boolean = jsonMapper.readTree(json) == jsonMapper.readTree(other)

    private data class Document(val currency: String, val items: List<Item>) {
        data class Item(val code: String, val name: String, val price: String, val category: String)
    }
}
