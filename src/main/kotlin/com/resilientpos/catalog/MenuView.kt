package com.resilientpos.catalog

import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import java.util.Currency

/** 저장된 메뉴와 그 [version]. ETag는 [storeId]와 [version]으로 만든다. */
data class MenuView(
    val storeId: StoreId,
    val currency: Currency,
    val items: List<Item>,
    val version: Long,
) {
    data class Item(
        val code: String,
        val name: String,
        val price: Money,
        val category: String,
    )
}
