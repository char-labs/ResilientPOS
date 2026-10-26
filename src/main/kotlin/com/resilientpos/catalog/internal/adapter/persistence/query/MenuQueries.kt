package com.resilientpos.catalog.internal.adapter.persistence.query

import com.resilientpos.catalog.internal.adapter.persistence.MenuDocumentMapper
import com.resilientpos.catalog.internal.adapter.persistence.command.MenuEntity
import com.resilientpos.catalog.internal.domain.StoredMenu
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.store.StoreId
import org.springframework.stereotype.Component

/** 메뉴 조회 구현. 매장의 PK 행 하나를 읽는다. port 인터페이스는 두지 않는다. */
@Component
class MenuQueries(
    private val queries: JdslQueryExecutor,
    private val documents: MenuDocumentMapper,
) {

    /** 없으면 null이다. 행 하나를 한 쿼리로 읽어 문서와 version이 같은 커밋의 것이다. */
    @ReadOnlyTransactional
    fun find(storeId: StoreId): StoredMenu? =
        queries.findFirst {
            selectFrom(MenuEntity::class).where(path(MenuEntity::storeId).eq(storeId.value))
        }?.let { StoredMenu(documents.toMenu(storeId, it.menu), checkNotNull(it.version) { "저장된 행은 version이 있다" }) }
}
