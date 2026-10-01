package com.resilientpos.sync.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.OrderId
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * operation 하나의 접수 기록. 단말의 `(terminalId, operationId)`마다 하나이고 한 번 쓰면 바뀌지 않는다.
 * payload hash가 재전송 판정의 근거다 — 같으면 duplicate, 다르면 conflict. `orderId`는 적용 결과의 참조라 재전송에 같은 값을 돌려준다.
 */
class SyncOperation private constructor(
    val id: SyncOperationId,
    val terminalId: TerminalId,
    val operationId: UUID,
    val sequence: Long,
    val kind: SyncOperationKind,
    val payloadHash: String,
    val status: SyncOperationStatus,
    val orderId: OrderId?,
    val errorCode: String?,
) {

    /** 같은 operation의 같은 내용 재전송인가. 순번까지 같아야 한다 — 다른 순번으로 온 같은 operation은 단말 상태가 갈라진 것이다. */
    fun matches(payloadHash: String, sequence: Long): Boolean = this.payloadHash == payloadHash && this.sequence == sequence

    override fun equals(other: Any?) = other is SyncOperation && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        fun applied(
            ids: IdentifierGenerator,
            terminalId: TerminalId,
            operationId: UUID,
            sequence: Long,
            kind: SyncOperationKind,
            payloadHash: String,
            orderId: OrderId,
        ): SyncOperation =
            SyncOperation(SyncOperationId(ids.next()), terminalId, operationId, sequence, kind, payloadHash, SyncOperationStatus.APPLIED, orderId, null)

        fun rejected(
            ids: IdentifierGenerator,
            terminalId: TerminalId,
            operationId: UUID,
            sequence: Long,
            kind: SyncOperationKind,
            payloadHash: String,
            errorCode: String,
        ): SyncOperation =
            SyncOperation(SyncOperationId(ids.next()), terminalId, operationId, sequence, kind, payloadHash, SyncOperationStatus.REJECTED, null, errorCode)

        /** 저장된 기록을 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(
            id: SyncOperationId,
            terminalId: TerminalId,
            operationId: UUID,
            sequence: Long,
            kind: SyncOperationKind,
            payloadHash: String,
            status: SyncOperationStatus,
            orderId: OrderId?,
            errorCode: String?,
        ): SyncOperation = SyncOperation(id, terminalId, operationId, sequence, kind, payloadHash, status, orderId, errorCode)
    }
}
