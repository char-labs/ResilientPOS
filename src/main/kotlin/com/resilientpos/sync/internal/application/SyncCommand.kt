package com.resilientpos.sync.internal.application

import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderId
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * `sync`의 쓰기 커맨드. 모듈 밖에서 부르는 쪽이 없어 [SyncCommandFacade]와 함께 `internal`에 둔다(fulfillment와 같다).
 */
object SyncCommand {

    /**
     * 한 단말이 오프라인에서 만든 operation 배치. 항목의 `order`는 `POST /v1/orders`와 같은 주문 생성 명령이고
     * 그 `terminalId`는 배치의 `terminalId`와 같다(adapter가 경로 변수로 채운다).
     */
    data class ReceiveBatch(
        val terminalId: TerminalId,
        val operations: List<Operation>,
    ) {
        data class Operation(
            val operationId: UUID,
            val sequence: Long,
            val order: OrderCommand.Create,
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
