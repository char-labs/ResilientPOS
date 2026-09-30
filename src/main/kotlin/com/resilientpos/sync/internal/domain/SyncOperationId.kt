package com.resilientpos.sync.internal.domain

import java.util.UUID

/**
 * 접수 기록의 서버 식별자(UUID v7). 단말이 만든 `operationId`와 다르다 — 그쪽은 `(terminalId, operationId)`로 유일하다.
 */
@JvmInline
value class SyncOperationId(val value: UUID)
