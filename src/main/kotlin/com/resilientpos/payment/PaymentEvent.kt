package com.resilientpos.payment

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID

/**
 * `payment` 모듈이 발행하는 도메인 이벤트의 닫힌 집합.
 * provider 결과를 적용하는 전이가 생기는 PR이 `PaymentDeclined`·`PaymentUnknown`을 더한다. *
 * `orderId`는 `order.OrderId`가 아니라 raw `UUID`다. `payment`가 `OrderId`를 import하면
 * `order`가 `PaymentApproved`를 소비하는 순간 `order -> payment -> order` 순환이 되어 Modulith 검증이 실패한다.
 * 실제로 재현해 확인했다. 타입 안전성은 `payment` 경계 안에서만 잃고, `order`는 받은 값을 `OrderId`로 감싼다.
 */
sealed interface PaymentEvent : DomainEvent

/**
 * 한 주문의 결제 시도를 만들었다는 사실. 한 주문에 여러 번 날 수 있고 `paymentId`가 그것을 가른다.
 */
data class PaymentAttemptCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val amount: Money,
) : PaymentEvent

/**
 * 시도가 승인됐다는 사실. `order`가 이 값을 checkout과 대조하고, 하나라도 다르면 reconciliation case를 연다.
 *
 * **`kind`를 보지 않고 결제 완료로 읽으면 안 된다.** 취소가 승인돼도 이 이벤트가 난다.
 *
 * `providerReference`는 provider를 실제로 부른 승인에만 있다. 0원 승인은 부르지 않았으므로 `null`이고,
 * 그 `null`이 "provider가 모르는 승인"이라는 사실을 그대로 나타낸다.
 */
data class PaymentApproved(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val kind: PaymentAttemptKind,
    val amount: Money,
    val providerReference: String?,
) : PaymentEvent
