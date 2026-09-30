package com.resilientpos.sync.internal.application

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.platform.Tx
import com.resilientpos.platform.logger
import com.resilientpos.sync.internal.domain.CheckoutPayload
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.sync.internal.domain.SyncOperation
import com.resilientpos.sync.internal.domain.SyncOperationStatus
import com.resilientpos.sync.internal.domain.SyncRepository
import com.resilientpos.sync.internal.domain.Watermark
import com.resilientpos.terminal.TerminalId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * 단말의 오프라인 operation 배치를 받는 `sync`의 유일한 진입점. 모듈 밖에서 부르는 쪽이 없어 `internal`에 둔다.
 *
 * 항목 하나가 로컬 트랜잭션 하나다(도메인 모델 §4.6). 그 안에서 watermark 행을 잠그고 판정하고, 다음 항목이면 종류에 따라 `order`의
 * 락 없는 합류 경로([OrderCommandFacade.createInCallerTransaction])나 checkout 요청([OrderCommandFacade.requestCheckout])을 불러
 * 접수 기록과 watermark 전진까지 한 커밋으로 한다. 같은 단말의 배치 둘은 watermark 행 잠금으로 항목 단위 직렬화된다.
 * `REQUEST_CHECKOUT` 항목은 그 다음에 주문 행을 잠그지만 순서가 늘 같고 각각 대기 3초다. Redis `Lock`은 이 경로에 없다.
 *
 * 인프라 실패(잠금 대기 초과·DB 장애·unique 위반)는 항목 결과가 아니다. 그 항목 트랜잭션은 롤백되고 예외가 배치 전체를 실패시킨다.
 * 이미 커밋된 앞 항목은 남고 재전송 때 `DUPLICATE`로 돌아온다.
 */
@Service
class SyncCommandFacade(
    private val repository: SyncRepository,
    private val orders: OrderCommandFacade,
    private val identifierGenerator: IdentifierGenerator,
) {

    fun receiveBatch(command: SyncCommand.ReceiveBatch): SyncCommand.ReceiveBatch.Result {
        // 가장 싼 거절을 가장 먼저 한다. 상한을 넘는 배치는 Redis도 커넥션도 건드리지 않는다.
        if (command.operations.size > MAX_OPERATIONS) {
            throw DomainException(SYNC_BATCH_TOO_LARGE, "한 배치의 operation은 ${MAX_OPERATIONS}개를 넘을 수 없습니다")
        }

        if (command.operations.isNotEmpty()) ensureWatermark(command.terminalId)

        // 순번 오름차순이라 한 번 gap이면 뒤는 모두 gap이다. 트랜잭션과 행 잠금 없이 채운다.
        var gapped = false
        val items = command.operations.sortedBy { it.sequence }.map { operation ->
            if (gapped) {
                SyncCommand.ReceiveBatch.Item(operation.operationId, SyncItemStatus.GAP, null, null)
            } else {
                applyOperation(command.terminalId, operation).also { gapped = it.status == SyncItemStatus.GAP }
            }
        }
        val lastAckedSequence = Tx.readOnlyTx { repository.findWatermark(command.terminalId) }?.lastAckedSequence ?: 0
        log.info {
            val counts = items.groupingBy { it.status }.eachCount()
            "sync 배치 처리 terminalId=${command.terminalId.value} operations=${items.size} lastAckedSequence=$lastAckedSequence counts=$counts"
        }
        return SyncCommand.ReceiveBatch.Result(lastAckedSequence, items)
    }

    /**
     * 잠글 watermark 행이 없는 새 단말이면 짧은 트랜잭션으로 먼저 넣는다. 행이 있어야 항목 잠금이 같은 단말의 배치를 직렬화한다.
     * 같은 단말의 첫 배치가 동시에 둘 오면 한쪽만 넣고 진 쪽은 PK 위반을 받는데, 그것은 "이미 있다"는 뜻이라 삼키고 진행한다.
     * 커밋 시점의 PK 위반은 `DuplicateKeyException`이 아니라 부모 `DataIntegrityViolationException`으로 번역된다(JpaTransactionManager). 이 트랜잭션은 INSERT 하나뿐이라 다른 제약이 없다.
     */
    private fun ensureWatermark(terminalId: TerminalId) {
        try {
            Tx.masterTx {
                if (repository.findWatermark(terminalId) == null) repository.saveWatermark(Watermark.initial(terminalId))
            }
        } catch (raced: DataIntegrityViolationException) {
            log.info { "watermark 행을 다른 배치가 먼저 넣었다 terminalId=${terminalId.value}" }
        }
    }

    /**
     * 주문 내용의 변환(`Money.of`), 주문 생성, checkout 요청(주문 없음, 금액 불일치 등)이 도메인 규칙으로 거절되면 그 트랜잭션은 롤백돼 그 항목이 쓴 행이 남지 않는다. 같은 판정을 두 번째 트랜잭션으로 다시 돌려
     * `REJECTED`를 기록하고 순번을 확정한다. 거절 판정은 대개 결정적이라 두 트랜잭션 사이에 죽어도 재전송이 같은 결과를 낸다. 단 주문 없음(`ORDER_NOT_FOUND`)은 시점에 따라 달라질 수 있어(그 사이 같은 clientOrderId의 주문이 HTTP로 커밋될 수 있다) 그때도 거절이 기록되고, 단말은 새 operationId로 다시 요청한다.
     * 두 번째 판정에서 순번을 다시 확인하므로 그 사이에 다른 배치가 같은 operation을 확정했으면 `DUPLICATE`·`CONFLICT`로 갈린다.
     */
    private fun applyOperation(terminalId: TerminalId, operation: SyncCommand.ReceiveBatch.Operation): SyncCommand.ReceiveBatch.Item {
        val payloadHash = operation.payload.hash()
        return try {
            Tx.masterTx {
                judge(terminalId, operation, payloadHash) {
                    val payload = operation.payload
                    val orderId = when (payload) {
                        is OrderPayload -> orders.createInCallerTransaction(payload.toCommand(terminalId)).orderId
                        is CheckoutPayload -> orders.requestCheckout(OrderCommand.RequestCheckoutByClientOrderId(terminalId, payload.clientOrderId, payload.amount)).orderId
                    }
                    SyncOperation.applied(identifierGenerator, terminalId, operation.operationId, operation.sequence, payload.kind, payloadHash, orderId)
                }
            }
        } catch (rejected: DomainException) {
            Tx.masterTx {
                judge(terminalId, operation, payloadHash) {
                    SyncOperation.rejected(identifierGenerator, terminalId, operation.operationId, operation.sequence, operation.payload.kind, payloadHash, rejected.code)
                }
            }
        }
    }

    /**
     * watermark 행을 잠근 채 판정한다. 다음 항목이면 [record]가 만든 접수 기록을 넣고 전진한다. 그 밖은 아무것도 쓰지 않는다.
     * 다음 순번인데 그 operationId가 이미 기록돼 있으면 `CONFLICT`다 — 단말이 operationId를 다른 순번에 다시 썼다. 그냥 넣으면
     * unique 위반이 매 재전송마다 같은 배치 오류로 되풀이되어 단말이 영구히 막히고 로그로만 드러난다.
     */
    private fun judge(
        terminalId: TerminalId,
        operation: SyncCommand.ReceiveBatch.Operation,
        payloadHash: String,
        record: () -> SyncOperation,
    ): SyncCommand.ReceiveBatch.Item {
        val watermark = checkNotNull(repository.findWatermarkForUpdate(terminalId)) { "watermark 행이 없다: ${terminalId.value}" }
        return when (watermark.judge(operation.sequence)) {
            Watermark.Position.NEXT -> {
                if (repository.findOperation(terminalId, operation.operationId) != null) {
                    return conflict(terminalId, operation, "이미 기록된 operation이 새 순번으로 왔다")
                }
                val recorded = repository.saveOperation(record())
                repository.saveWatermark(watermark.advance())
                SyncCommand.ReceiveBatch.Item(operation.operationId, recorded.status.toItemStatus(), recorded.orderId, recorded.errorCode)
            }
            Watermark.Position.ACKED -> {
                val recorded = repository.findOperation(terminalId, operation.operationId)
                when {
                    recorded == null -> conflict(terminalId, operation, "확정된 순번에 모르는 operation")
                    !recorded.matches(payloadHash, operation.sequence) -> conflict(terminalId, operation, "확정된 operation에 다른 내용이나 다른 순번")
                    else -> SyncCommand.ReceiveBatch.Item(operation.operationId, SyncItemStatus.DUPLICATE, recorded.orderId, recorded.errorCode)
                }
            }
            Watermark.Position.GAP -> SyncCommand.ReceiveBatch.Item(operation.operationId, SyncItemStatus.GAP, null, null)
        }
    }

    /** 단말과 서버의 상태가 갈라졌다는 신호라 항목마다 WARN을 남긴다. */
    private fun conflict(terminalId: TerminalId, operation: SyncCommand.ReceiveBatch.Operation, reason: String): SyncCommand.ReceiveBatch.Item {
        log.warn { "sync 충돌 terminalId=${terminalId.value} operationId=${operation.operationId} sequence=${operation.sequence} : $reason" }
        return SyncCommand.ReceiveBatch.Item(operation.operationId, SyncItemStatus.CONFLICT, null, null)
    }

    private fun SyncOperationStatus.toItemStatus(): SyncItemStatus = when (this) {
        SyncOperationStatus.APPLIED -> SyncItemStatus.APPLIED
        SyncOperationStatus.REJECTED -> SyncItemStatus.REJECTED
    }

    companion object {
        private val log by logger()

        /** 한 배치에 담을 수 있는 operation 수. 기획 원문의 "500개 batch"를 상한으로 둔다. */
        const val MAX_OPERATIONS = 500
        const val SYNC_BATCH_TOO_LARGE = "SYNC_BATCH_TOO_LARGE"
    }
}
