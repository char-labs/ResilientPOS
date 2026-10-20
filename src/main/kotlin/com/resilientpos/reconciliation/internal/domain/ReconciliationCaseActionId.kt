package com.resilientpos.reconciliation.internal.domain

import java.util.UUID

/** 감사 기록 한 줄의 식별자(UUID v7). 저장 전에 `IdentifierGenerator`로 만든다. */
@JvmInline
value class ReconciliationCaseActionId(val value: UUID)
