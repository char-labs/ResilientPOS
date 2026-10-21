package com.resilientpos.reconciliation.internal.application

import com.resilientpos.contract.PaymentReconciliationRequested
import com.resilientpos.payment.PaymentId
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.platform.logger
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `payment`가 시도를 넘긴 사실을 받아 대조 case를 연다. 옮기는 일만 하고 판단하지 않는다. 멱등 판정과 트랜잭션은 [ReconciliationManagement]가 소유한다.
 *
 * 여기서 던지면 `KafkaConfiguration`의 오류 처리기가 다시 시도하고 끝내 실패하면 DLT로 보낸다. 같은 시도의 사실이 겹쳐 처리돼
 * 커밋에서 unique 위반(`DataIntegrityViolationException`)이 나도 재시도가 기존 case를 찾는다. 예외를 삼키지 않는다 — 삼키면 offset이 넘어가 흔적 없이 사라진다.
 */
@Component
class PaymentReconciliationRequestedListener(
    private val reconciliationManagement: ReconciliationManagement,
) {

    @KafkaListener(topics = [PaymentReconciliationRequested.TOPIC], groupId = "reconciliation.payment-reconciliation-requested")
    fun on(event: PaymentReconciliationRequested) {
        val result = reconciliationManagement.open(
            ReconciliationCommand.Open(
                occurredAt = event.occurredAt,
                paymentId = PaymentId(event.paymentId),
                orderId = event.orderId,
                storeId = event.storeId,
                terminalId = event.terminalId,
                checkoutNo = event.checkoutNo,
                amount = event.amount,
                reason = event.reason,
                unknownReason = event.unknownReason,
                queryCount = event.queryCount,
                reportedAmount = event.reportedAmount,
                reportedProviderReference = event.reportedProviderReference,
            ),
        )

        log.debug { "대조 case 열기 처리 paymentId=${event.paymentId} caseId=${result.caseId.value} status=${result.status}" }
    }

    companion object {
        private val log by logger()
    }
}
