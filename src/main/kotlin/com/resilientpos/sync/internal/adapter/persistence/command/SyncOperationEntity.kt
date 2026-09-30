package com.resilientpos.sync.internal.adapter.persistence.command

import com.resilientpos.order.OrderId
import com.resilientpos.platform.BaseEntity
import com.resilientpos.sync.internal.domain.SyncOperation
import com.resilientpos.sync.internal.domain.SyncOperationId
import com.resilientpos.sync.internal.domain.SyncOperationStatus
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * `SyncOperation`의 JPA 매핑. 넣기만 하고 바꾸지 않아 `apply`가 없다. 생성은 `from`만 한다(도메인 규칙 §5).
 * 전 필드가 `var`에 `protected set`인 이유는 `OrderEntity`의 KDoc에 있다.
 */
@Entity
@Table(name = "t_sync_operation")
class SyncOperationEntity private constructor(
    id: UUID,
    terminalId: UUID,
    operationId: UUID,
    sequence: Long,
    payloadHash: String,
    status: SyncOperationStatus,
    orderId: UUID?,
    errorCode: String?,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var terminalId: UUID = terminalId
        protected set

    var operationId: UUID = operationId
        protected set

    var sequence: Long = sequence
        protected set

    var payloadHash: String = payloadHash
        protected set

    @Enumerated(EnumType.STRING)
    var status: SyncOperationStatus = status
        protected set

    var orderId: UUID? = orderId
        protected set

    var errorCode: String? = errorCode
        protected set

    fun toDomain(): SyncOperation = SyncOperation.restore(
        id = SyncOperationId(id),
        terminalId = TerminalId(terminalId),
        operationId = operationId,
        sequence = sequence,
        payloadHash = payloadHash,
        status = status,
        orderId = orderId?.let(::OrderId),
        errorCode = errorCode,
    )

    companion object {
        fun from(operation: SyncOperation): SyncOperationEntity = SyncOperationEntity(
            id = operation.id.value,
            terminalId = operation.terminalId.value,
            operationId = operation.operationId,
            sequence = operation.sequence,
            payloadHash = operation.payloadHash,
            status = operation.status,
            orderId = operation.orderId?.value,
            errorCode = operation.errorCode,
        )
    }
}
