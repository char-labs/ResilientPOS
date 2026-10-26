package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuCommand
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId
import java.math.BigDecimal

/**
 * `PUT /owner/v1/stores/{storeId}/menu`의 요청 본문. 형식 오류(필수 필드 누락, null)는 Jackson 역직렬화가 거절한다.
 * `currency`를 `Currency`가 아닌 코드 문자열로 받는다. 모르는 코드는 Facade가 `INVALID_MENU`로 바꾼다(역직렬화 실패면 `BAD_REQUEST`가 된다).
 * `price`는 메뉴 통화의 자릿수를 따르는 decimal string이다.
 */
data class ReplaceMenuRequest(
    val currency: String,
    val items: List<Item>,
) {
    data class Item(
        val code: String,
        val name: String,
        val price: BigDecimal,
        val category: String,
    )

    fun toCommand(storeId: StoreId, ownerId: OwnerId, precondition: MenuCommand.Precondition) = MenuCommand.Replace(
        storeId = storeId,
        ownerId = ownerId,
        currency = currency,
        items = items.map { MenuCommand.Item(it.code, it.name, it.price, it.category) },
        precondition = precondition,
    )
}
