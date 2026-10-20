package com.resilientpos.reconciliation

import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/** `reconciliation`의 쓰기 커맨드. use case마다 커맨드 하나다. */
object ReconciliationCommand {

    /**
     * 넘겨받은 시도로 case를 연다. 필드는 contract `PaymentReconciliationRequested`와 같고 `eventId`만 없다.
     * [occurredAt]은 넘긴 시각이며 case를 여는 감사 줄의 시각이 된다. `orderId`가 raw `UUID`인 이유는 그 이벤트와 같다.
     */
    data class Open(
        val occurredAt: Instant,
        val paymentId: PaymentId,
        val orderId: UUID,
        val storeId: StoreId,
        val terminalId: TerminalId,
        val checkoutNo: Int,
        val amount: Money,
        val reason: PaymentReconciliationReason,
        val unknownReason: PaymentUnknownReason,
        val queryCount: Int,
        val reportedAmount: Money?,
        val reportedProviderReference: String?,
    ) {
        /** case 열기 결과. 같은 시도의 case가 이미 있으면 그 case다. */
        data class Result(
            val caseId: ReconciliationCaseId,
            val status: ReconciliationCaseStatus,
        )
    }

    /**
     * 점주가 case를 [decision]으로 해소한다. 근거는 PG 승인 참조([providerReference])와 PG 화면에서 본 승인 금액([approvedAmount]),
     * 사유([note])다. [storeId]는 case를 매장 조건으로 찾는 데 쓴다.
     */
    data class Resolve(
        val caseId: ReconciliationCaseId,
        val storeId: StoreId,
        val ownerId: OwnerId,
        val decision: ReconciliationDecision,
        val providerReference: String?,
        val approvedAmount: Money?,
        val note: String,
    ) {
        /**
         * 해소 결과. [paymentStatus]는 결론을 적용한 뒤(또는 이미 해소된 case면 결론에 대응하는) 시도의 상태다.
         */
        data class Result(
            val caseId: ReconciliationCaseId,
            val status: ReconciliationCaseStatus,
            val decision: ReconciliationDecision,
            val paymentId: PaymentId,
            val paymentStatus: PaymentAttemptStatus,
        )
    }
}
