package com.resilientpos.payment

import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * `payment`의 쓰기 커맨드. use case마다 커맨드 하나이고, 그 결과는 커맨드 안에 둔다.
 * 시도 생성, 승인 요청, 재결제, 결과 불명 확정이 각각 자기 트랜잭션이다.
 */
object PaymentCommand {

    /**
     * 한 주문의 `checkoutNo`회차 확정 금액으로 결제 시도를 만든다.
     * `order`가 낸 `OrderPaymentRequested`의 값을 그대로 옮긴 것이고, `payment`는 `order`의 테이블을 읽지 않는다.
     * `orderId`가 raw `UUID`인 이유는 [PaymentEvent]에 있다.
     */
    data class CreateAttempt(
        val orderId: UUID,
        val storeId: StoreId,
        val terminalId: TerminalId,
        val checkoutNo: Int,
        val amount: Money,
    ) {
        /**
         * 시도 생성 결과. 0원 주문이면 생성과 동시에 [PaymentAttemptStatus.APPROVED]다.
         */
        data class Result(
            val paymentId: PaymentId,
            val status: PaymentAttemptStatus,
        )
    }

    /**
     * 이미 기록된 시도에 provider 승인을 요청하고 그 결과를 적용한다.
     */
    data class Authorize(
        val paymentId: PaymentId,
    ) {
        /**
         * 승인 요청 결과. 이미 결말이 난 시도였으면 provider를 부르지 않고 그 결말을 그대로 돌려준다.
         */
        data class Result(
            val paymentId: PaymentId,
            val status: PaymentAttemptStatus,
        )
    }

    /**
     * 거절된 승인 시도를 같은 checkout 회차에서 다시 시도한다. 주문이 아니라 거절된 시도를 가리켜서,
     * 응답을 잃고 다시 보낸 요청과 새 재결제 의도를 구분한다.
     */
    data class RetryAuthorization(
        val paymentId: PaymentId,
    ) {
        /**
         * 새로 만든 시도, 또는 이미 다시 시도했으면 그 회차의 최신 시도.
         */
        data class Result(
            val paymentId: PaymentId,
            val status: PaymentAttemptStatus,
        )
    }

    /**
     * 결과를 모른 채 남은([PaymentAttemptStatus.UNKNOWN]) 승인 시도를 provider 조회로 확정한다.
     */
    data class ResolveUnknown(
        val paymentId: PaymentId,
    ) {
        /**
         * 확정 결과. `UNKNOWN`이 아니었거나 조회로도 확인하지 못했으면 provider 결과를 적용하지 않고 지금 상태를 그대로 돌려준다.
         */
        data class Result(
            val paymentId: PaymentId,
            val status: PaymentAttemptStatus,
        )
    }
}
