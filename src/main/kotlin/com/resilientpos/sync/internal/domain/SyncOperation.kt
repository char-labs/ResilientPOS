package com.resilientpos.sync.internal.domain

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderId
import com.resilientpos.terminal.TerminalId
import java.security.MessageDigest
import java.util.HexFormat
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
    val payloadHash: String,
    val status: SyncOperationStatus,
    val orderId: OrderId?,
    val errorCode: String?,
) {

    fun matches(payloadHash: String): Boolean = this.payloadHash == payloadHash

    override fun equals(other: Any?) = other is SyncOperation && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        fun applied(
            ids: IdentifierGenerator,
            terminalId: TerminalId,
            operationId: UUID,
            sequence: Long,
            payloadHash: String,
            orderId: OrderId,
        ): SyncOperation =
            SyncOperation(SyncOperationId(ids.next()), terminalId, operationId, sequence, payloadHash, SyncOperationStatus.APPLIED, orderId, null)

        fun rejected(
            ids: IdentifierGenerator,
            terminalId: TerminalId,
            operationId: UUID,
            sequence: Long,
            payloadHash: String,
            errorCode: String,
        ): SyncOperation =
            SyncOperation(SyncOperationId(ids.next()), terminalId, operationId, sequence, payloadHash, SyncOperationStatus.REJECTED, null, errorCode)

        /** 저장된 기록을 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(
            id: SyncOperationId,
            terminalId: TerminalId,
            operationId: UUID,
            sequence: Long,
            payloadHash: String,
            status: SyncOperationStatus,
            orderId: OrderId?,
            errorCode: String?,
        ): SyncOperation = SyncOperation(id, terminalId, operationId, sequence, payloadHash, status, orderId, errorCode)

        /**
         * 주문 생성 명령의 SHA-256 hex. 필드를 직접 이어 만든다 — `toString`에 기대면 `Money` 표현이 바뀔 때
         * 이미 기록된 hash가 어긋나 재전송이 conflict가 된다. line 순서가 다르면 다른 hash다. 단말은 저장한 봉투를 그대로 다시 보낸다.
         */
        fun payloadHashOf(command: OrderCommand.Create): String {
            val canonical = buildString {
                append(command.storeId.value).append('|')
                append(command.clientOrderId.value).append('|')
                append(command.currency.currencyCode).append('|')
                command.lines.forEach { line ->
                    append(line.productCode).append('|')
                    append(line.productName).append('|')
                    append(line.unitPrice.amount.toPlainString()).append('|')
                    append(line.quantity).append('|')
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
