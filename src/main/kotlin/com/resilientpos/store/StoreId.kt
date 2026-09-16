package com.resilientpos.store

import java.util.UUID

/**
 * 매장 식별자. `store` 모듈이 소유하는 aggregate가 아직 없어 ID 값 타입만 공개한다.
 */
@JvmInline
value class StoreId(val value: UUID)
