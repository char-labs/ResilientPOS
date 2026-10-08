package com.resilientpos.store

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.Tx
import com.resilientpos.store.internal.adapter.persistence.query.StoreQueries
import com.resilientpos.store.internal.domain.Store
import com.resilientpos.store.internal.domain.StoreRepository
import org.springframework.stereotype.Service

/**
 * `store` 모듈의 유일한 진입점. 매장 생성, 내 매장 목록, 소유 확인이 여기를 거친다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 읽기도 이 클래스에 둔다(메서드가 셋이고 쓰기와 같은 소유 규칙을 공유한다).
 */
@Service
class StoreManagement(
    private val repository: StoreRepository,
    private val queries: StoreQueries,
    private val identifierGenerator: IdentifierGenerator,
) {

    /** 이름을 검증해 매장을 만든다. 이름이 1~100자가 아니면 `INVALID_STORE_NAME`이다. */
    fun create(command: StoreCommand.Create): StoreCommand.Create.Result {
        val store = Store.create(command.ownerId, command.name, identifierGenerator)
        Tx.masterTx { repository.save(store) }
        return StoreCommand.Create.Result(store.id, store.name)
    }

    /** 그 점주의 매장을 생성 순(created_at, id)으로 돌려준다. 없으면 빈 목록이다. */
    fun findByOwner(ownerId: OwnerId): List<StoreView> =
        queries.findByOwner(ownerId)

    /** 매장이 그 점주의 것이 아니면 `STORE_NOT_FOUND`(NOT_FOUND)다. 없는 매장과 남의 매장을 구분하지 않는다. */
    fun requireOwned(storeId: StoreId, ownerId: OwnerId) {
        if (!queries.isOwnedBy(storeId, ownerId)) throw Store.notFound()
    }
}
