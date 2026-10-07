package com.resilientpos.store.internal.domain

/** `Store`를 저장하는 port. 트랜잭션 안에서만 부른다. 읽기는 aggregate를 거치지 않는 `StoreQueries`가 맡는다. */
interface StoreRepository {
    fun save(store: Store): Store
}
