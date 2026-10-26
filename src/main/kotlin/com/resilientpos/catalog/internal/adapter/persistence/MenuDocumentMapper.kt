package com.resilientpos.catalog.internal.adapter.persistence

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.resilientpos.catalog.MenuView
import com.resilientpos.catalog.internal.domain.Menu
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

    /**
     * 저장 문서를 [Menu.create]로 다시 검증하지 않고 읽기용 [MenuView]로 바꾼다. 규칙이 강해진 뒤에도 이미 저장된 메뉴를 읽을 수 있어야 한다.
     * 문서를 읽지 못하면(모르는 통화, 자릿수 위반) 사용자 잘못이 아니라 저장 데이터의 문제라 [IllegalStateException]이다.
     */
    fun toView(storeId: StoreId, json: String, version: Long): MenuView =
        try {
            val document = jsonMapper.readValue(json, Document::class.java)
            val currency = Currency.getInstance(document.currency)
            MenuView(
                storeId,
                currency,
                document.items.map { MenuView.Item(it.code, it.name, Money.of(it.price, currency), it.category) },
                version,
            )
        } catch (e: Exception) {
            throw IllegalStateException("저장된 메뉴 문서를 읽을 수 없다: storeId=${storeId.value}", e)
        }

    /** 두 문서가 JSON 값으로 같은지. 공백과 키 순서는 보지 않는다. */
    fun isSame(json: String, other: String): Boolean = jsonMapper.readTree(json) == jsonMapper.readTree(other)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Document(val currency: String, val items: List<Item>) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Item(val code: String, val name: String, val price: String, val category: String)
    }
}
