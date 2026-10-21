package com.resilientpos.reconciliation

import java.util.UUID

/** 대조 case의 서버 식별자(UUID v7). 저장 전에 `IdentifierGenerator`로 만든다. */
@JvmInline
value class ReconciliationCaseId(val value: UUID)
