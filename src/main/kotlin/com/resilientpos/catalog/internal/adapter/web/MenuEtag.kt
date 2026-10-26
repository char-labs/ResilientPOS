package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuView
import com.resilientpos.store.StoreId

/**
 * 메뉴의 strong ETag `"매장ID.version"`. 매장 ID가 없으면 모든 매장의 첫 메뉴가 version 0이라
 * 다른 매장의 사본을 가진 기기가 304를 받는다.
 */
object MenuEtag {

    fun of(view: MenuView): String = "\"${view.storeId.value}.${view.version}\""

    private val STRONG = Regex("\"([^\",]*)\\.([0-9]+)\"")

    /** [header]가 이 매장의 strong ETag 하나일 때만 version을 돌려준다. `*`·weak·여러 값·다른 매장·해석 불가는 null이다. */
    fun versionOf(header: String, storeId: StoreId): Long? {
        val match = STRONG.matchEntire(header.trim()) ?: return null
        if (match.groupValues[1] != storeId.value.toString()) return null
        return match.groupValues[2].toLongOrNull()
    }
}
