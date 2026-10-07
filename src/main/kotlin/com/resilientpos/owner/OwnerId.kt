package com.resilientpos.owner

import java.util.UUID

/** 점주 식별자. [OwnerManagement]가 소유하는 `Owner` aggregate의 ID이고, 다른 모듈은 이 값 타입으로만 점주를 가리킨다. */
@JvmInline
value class OwnerId(val value: UUID)
