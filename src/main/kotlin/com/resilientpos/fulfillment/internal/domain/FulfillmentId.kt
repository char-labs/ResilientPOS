package com.resilientpos.fulfillment.internal.domain

import java.util.UUID

/**
 * fulfillment의 서버 식별자(UUID v7). 저장 전에 `IdentifierGenerator`로 만든다.
 */
@JvmInline
value class FulfillmentId(val value: UUID)
