package com.resilientpos.sync.internal.application

import com.resilientpos.order.OrderId
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.domain.OperationPayload
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * `sync`의 쓰기 커맨드. 모듈 밖에서 부르는 쪽이 없어 [SyncCommandFacade]와 함께 `internal`에 둔다(fulfillment와 같다).
 */
object SyncCommand {

    /**
     * 한 단말이 오프라인에서 만든 operation 배치. 항목의 `payload`는 단말이 보낸 내용 그대로([OperationPayload])이고
     * `order` 명령으로의 변환은 Facade가 항목 트랜잭션 안에서 한다. 단말 ID와 `storeId`(인증된 단말의 매장)는 배치에만 있다.
     */
    data class ReceiveBatch(
        val terminalId: TerminalId,
        val storeId: StoreId,
        val operations: List<Operation>,
    ) {
        data class Operation(
            val operationId: UUID,
            val sequence: Long,
            val payload: OperationPayload,
        )

        /** 항목별 결과와 배치가 끝난 시점의 watermark. */
        data class Result(
            val lastAckedSequence: Long,
            val items: List<Item>,
        )

        /** `orderId`는 적용됐거나 적용된 기록의 재전송일 때, `errorCode`는 거절됐거나 거절된 기록의 재전송일 때 있다. */
        data class Item(
            val operationId: UUID,
            val status: SyncItemStatus,
            val orderId: OrderId?,
            val errorCode: String?,
        )
    }
}
