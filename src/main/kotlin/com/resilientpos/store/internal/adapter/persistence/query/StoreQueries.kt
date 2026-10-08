package com.resilientpos.store.internal.adapter.persistence.query

import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.platform.selectFrom
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreView
import com.resilientpos.store.internal.adapter.persistence.command.StoreEntity
import org.springframework.stereotype.Component

/** 매장 조회 구현. aggregate를 거치지 않고 엔티티를 바로 [StoreView]로 바꾼다. port 인터페이스는 두지 않는다. */
@Component
class StoreQueries(private val queries: JdslQueryExecutor) {

    /** `ix_store_owner_id`를 탄다. 점주 한 명의 매장 수는 한정적이라 페이지네이션이 없다. */
    @ReadOnlyTransactional
    fun findByOwner(ownerId: OwnerId): List<StoreView> =
        queries.findAll {
            selectFrom(StoreEntity::class)
                .where(path(StoreEntity::ownerId).eq(ownerId.value))
                .orderBy(path(StoreEntity::createdAt).asc(), path(StoreEntity::id).asc())
        }.map { StoreView(StoreId(it.id), it.name) }

    /** 그 점주의 매장이면 true. 없는 매장과 남의 매장은 모두 false다. */
    @ReadOnlyTransactional
    fun isOwnedBy(storeId: StoreId, ownerId: OwnerId): Boolean =
        queries.findFirst {
            selectFrom(StoreEntity::class).where(
                and(path(StoreEntity::id).eq(storeId.value), path(StoreEntity::ownerId).eq(ownerId.value)),
            )
        } != null
}
