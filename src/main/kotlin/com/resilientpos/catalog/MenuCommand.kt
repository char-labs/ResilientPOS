package com.resilientpos.catalog

import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId
import java.math.BigDecimal

/** `catalog`의 쓰기 커맨드. */
object MenuCommand {

    /**
     * 매장 메뉴 전체를 교체한다. [currency]는 코드 문자열이고 `Currency`가 모르는 코드(소문자 포함)는 `INVALID_MENU`다.
     * [precondition]이 만들기인지 지금 version 기준 고치기인지 정한다(헤더 해석은 Controller의 몫이다).
     */
    data class Replace(
        val storeId: StoreId,
        val ownerId: OwnerId,
        val currency: String,
        val items: List<Item>,
        val precondition: Precondition,
    )

    /** 요청 본문의 메뉴 항목. [price]는 메뉴 통화의 자릿수를 따르는 금액이다. */
    data class Item(
        val code: String,
        val name: String,
        val price: BigDecimal,
        val category: String,
    )

    /** 저장 조건. 둘 다 어긋나면 `MENU_VERSION_MISMATCH`(CONFLICT)다. */
    sealed interface Precondition {
        /** 메뉴가 아직 없어야 한다(`If-None-Match: *`). */
        data object Create : Precondition

        /** 저장된 version이 [expected]와 같아야 한다(`If-Match`). 메뉴가 없어도 어긋난 것이다. */
        data class Version(val expected: Long) : Precondition
    }
}
