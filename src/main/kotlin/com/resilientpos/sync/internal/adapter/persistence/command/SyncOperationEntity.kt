package com.resilientpos.sync.internal.adapter.persistence.command

import com.resilientpos.order.OrderId
import com.resilientpos.platform.BaseEntity
import com.resilientpos.sync.internal.domain.SyncOperation
import com.resilientpos.sync.internal.domain.SyncOperationId
import com.resilientpos.sync.internal.domain.SyncOperationKind
import com.resilientpos.sync.internal.domain.SyncOperationStatus
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * 역할: 단말이 만든 operation 하나를 서버가 어떻게 판정했는지(`APPLIED`·`REJECTED`), 무엇을 받았는지(`kind`·`payloadHash`), 결과가 무엇인지(`orderId`·`errorCode`).
 * 관계: `terminalId`·`operationId`가 단말 쪽 키, `sequence`가 확정한 순번, `orderId`가 만들어졌거나 결제를 요청한 `OrderEntity`다.
 * 흐름: sync 항목이 다음 순번으로 확정될 때 종류에 따라 주문 생성 또는 checkout 확정, watermark 전진과 한 커밋으로 생긴다. 같은 operation이 다시 오면 이 행으로 `DUPLICATE`/`CONFLICT`를 가른다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
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
    kind: SyncOperationKind,
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

    @Enumerated(EnumType.STRING)
    var kind: SyncOperationKind = kind
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
        kind = kind,
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
            kind = operation.kind,
            payloadHash = operation.payloadHash,
            status = operation.status,
            orderId = operation.orderId?.value,
            errorCode = operation.errorCode,
        )
    }
}
