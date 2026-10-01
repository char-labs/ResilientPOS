package com.resilientpos.sync.internal.domain

/**
 * operation의 종류. 이름이 단말 요청의 `kind` 값, `t_sync_operation.kind`의 저장 값, `payload_hash` 공식의 접두어 세 곳에 쓰인다.
 * 이름을 바꾸지 않는다. hash는 단방향이라 다시 계산할 수 없어서 확정된 operation의 재전송이 모두 conflict가 된다.
 */
enum class SyncOperationKind { CREATE_ORDER, REQUEST_CHECKOUT }
