package com.resilientpos.payment

import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * 한 주문의 `checkoutNo`회차 확정 금액으로 결제 시도를 만든다.
 * `order`가 낸 `OrderPaymentRequested`의 값을 그대로 옮긴 것이고, `payment`는 `order`의 테이블을 읽지 않는다. *
 * `orderId`는 `order.OrderId`가 아니라 raw `UUID`다. `payment`가 `OrderId`를 import하면
 * `order`가 `PaymentApproved`를 소비하는 순간 `order -> payment -> order` 순환이 되어 Modulith 검증이 실패한다.
 * 실제로 재현해 확인했다. 타입 안전성은 `payment` 경계 안에서만 잃고, `order`는 받은 값을 `OrderId`로 감싼다.
 */
data class CreatePaymentAttemptCommand(
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val amount: Money,
)

/**
 * 시도 생성 결과. 0원 주문이면 생성과 동시에 [PaymentAttemptStatus.APPROVED]다.
 */
data class CreatePaymentAttemptResult(
    val paymentId: PaymentId,
    val attemptNo: Int,
    val status: PaymentAttemptStatus,
)
