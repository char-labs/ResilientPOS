package com.resilientpos.contract

import com.resilientpos.foundation.DomainEvent
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.UUID
import org.springframework.modulith.events.Externalized

/**
 * 주문의 checkout 회차가 결제 승인과 대조되어 `PAID`가 됐다는 사실. `fulfillment`가 이 값을 받아 `Fulfillment`를 만든다.
 *
 * `orderId`가 `order.OrderId`가 아니라 raw `UUID`다. 계약이 `order`처럼 계약을 의존하는 모듈의 타입을 들면
 * 그 방향으로 간선이 생겨 끊으려던 순환이 되돌아온다. 받는 쪽이 자기 타입으로 감싼다.
 * `StoreId`·`TerminalId`는 `contract`를 의존하지 않는 모듈의 타입이라 순환이 없다(`PaymentApproved`와 같다).
 *
 * `storeId`·`terminalId`를 싣는다. 보드는 매장별로 조회하고 영수증은 주문을 낸 단말로 가야 하는데,
 * 기본값이 없어 나중에 더하면 영구 nullable이 되므로 저장 형식이 처음 생기는 지금 넣는다.
 */
@Externalized(OrderPaid.TOPIC + "::#{#this.orderId}")
data class OrderPaid(
    override val eventId: UUID,
    override val occurredAt: Instant,
    val orderId: UUID,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val checkoutNo: Int,
    val amount: Money,
) : DomainEvent {
    companion object {
        /** 토픽 이름은 저장 형식이다. key는 `orderId`라 같은 주문의 메시지가 한 파티션에 차례로 선다(도메인 모델 §4.3). */
        const val TOPIC = "order.paid"
    }
}
