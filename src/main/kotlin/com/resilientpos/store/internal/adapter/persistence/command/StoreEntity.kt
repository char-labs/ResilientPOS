package com.resilientpos.store.internal.adapter.persistence.command

import com.resilientpos.platform.BaseEntity
import com.resilientpos.store.internal.domain.Store
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * 역할: 점주가 소유한 매장 하나. 소속(`ownerId`)과 이름의 정본이다.
 * 흐름: 매장 생성이 한 번 만들고 바꾸는 경로는 없다. 행 갱신이 없어 `@Version`을 두지 않는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `Store`의 JPA 매핑. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 */
@Entity
@Table(name = "t_store")
class StoreEntity private constructor(
    id: UUID,
    ownerId: UUID,
    name: String,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var ownerId: UUID = ownerId
        protected set

    var name: String = name
        protected set

    companion object {
        fun from(store: Store): StoreEntity = StoreEntity(store.id.value, store.ownerId.value, store.name)
    }
}
