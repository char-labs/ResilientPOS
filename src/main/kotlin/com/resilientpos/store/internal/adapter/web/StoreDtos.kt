package com.resilientpos.store.internal.adapter.web

import com.resilientpos.store.StoreView
import java.util.UUID

/** `POST /owner/v1/stores` 요청. 이름 검증은 `Store`가 한다. */
data class CreateStoreRequest(val name: String)

/** 생성과 목록 항목의 응답. */
data class StoreResponse(val storeId: UUID, val name: String) {
    companion object {
        fun from(view: StoreView) = StoreResponse(view.storeId.value, view.name)
    }
}
