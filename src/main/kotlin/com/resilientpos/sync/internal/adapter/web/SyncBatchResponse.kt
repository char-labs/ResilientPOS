package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.sync.internal.application.SyncCommand
import com.resilientpos.sync.internal.application.SyncItemStatus
import java.util.UUID

/**
 * `POST /v1/terminals/{terminalId}/sync`의 성공 응답 본문. 항목 판정은 `items[].status`에만 있고 HTTP 상태는 배치 처리의 결과다.
 */
data class SyncBatchResponse(
    val lastAckedSequence: Long,
    val items: List<SyncItemResponse>,
) {
    companion object {
        fun from(result: SyncCommand.ReceiveBatch.Result) =
            SyncBatchResponse(result.lastAckedSequence, result.items.map(SyncItemResponse::from))
    }
}

/** `orderId`·`errorCode`는 해당 없으면 null이다. */
data class SyncItemResponse(
    val operationId: UUID,
    val status: SyncItemStatus,
    val orderId: UUID?,
    val errorCode: String?,
) {
    companion object {
        fun from(item: SyncCommand.ReceiveBatch.Item) =
            SyncItemResponse(item.operationId, item.status, item.orderId?.value, item.errorCode)
    }
}
