package com.resilientpos.sync.internal.domain

/** operation의 종류. 단말 요청의 `kind` 값이자 `t_sync_operation.kind`의 저장 값이라 이름을 바꾸면 계약과 데이터가 함께 바뀐다. */
enum class SyncOperationKind { CREATE_ORDER, REQUEST_CHECKOUT }
