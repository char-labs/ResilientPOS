package com.resilientpos.store

import com.resilientpos.owner.OwnerId

/** `store`의 쓰기 커맨드. */
object StoreCommand {

    /** 점주의 매장 하나를 만든다. [name]은 trim 전의 입력이고 1~100자(code point)여야 한다. */
    data class Create(val ownerId: OwnerId, val name: String) {
        /** [name]은 trim한 저장 값이다. */
        data class Result(val storeId: StoreId, val name: String)
    }
}
