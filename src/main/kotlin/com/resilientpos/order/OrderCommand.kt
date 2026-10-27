package com.resilientpos.order

import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * `order`의 쓰기 커맨드. use case마다 커맨드 하나이고, 그 입력 조각과 결과는 커맨드 안에 둔다.
 * `OrderCommandFacade`의 공개 입력과 결과이며 검증 로직을 갖지 않는다.
 */
object OrderCommand {

    /**
     * 주문 생성 요청. `OrderCommandFacade.create`의 입력이다.
     */
    data class Create(
        val storeId: StoreId,
        val terminalId: TerminalId,
        val clientOrderId: ClientOrderId,
        val currency: Currency,
        val lines: List<Line>,
        /** 단말이 알려 준 판매 시각. HTTP 생성은 null이다. 규칙은 `Order.create`가 적용한다. */
        val occurredAt: Instant?,
    ) {
        /**
         * 주문 생성 요청에 담긴 line 입력.
         */
        data class Line(
            val productCode: String,
            val productName: String,
            val unitPrice: Money,
            val quantity: Int,
        )

        /**
         * 주문 생성 결과.
         */
        data class Result(
            val orderId: OrderId,
            val status: OrderStatus,
        )
    }

    /**
     * 주문의 결제 요청. `OrderCommandFacade.requestCheckout`의 입력이다.
     */
    data class RequestCheckout(
        val orderId: OrderId,
        val storeId: StoreId,
        val terminalId: TerminalId,
    ) {
        /**
         * 결제 요청 결과. `checkoutNo`회차에 확정한 금액이 `amount`다. 재요청이면 이미 확정된 값을 그대로 담는다.
         */
        data class Result(
            val orderId: OrderId,
            val status: OrderStatus,
            val checkoutNo: Int,
            val amount: Money,
        )
    }

    /**
     * 단말이 아는 `clientOrderId`로 찾는 주문의 결제 요청. `OrderCommandFacade.requestCheckout`의 오버로드 입력이고
     * 결과는 [RequestCheckout.Result]를 그대로 쓴다. `orderId`를 모르는 sync 경로가 쓴다.
     * `clientOrderId`만으로는 단말이 어느 내용의 주문을 가리키는지 알 수 없어 단말이 본 합계 `expectedAmount`를 싣고,
     * 주문의 확정 금액과 다르면 거절한다. `Money` 변환은 주문의 통화로 `order` 안에서 한다.
     */
    data class RequestCheckoutByClientOrderId(
        val terminalId: TerminalId,
        val clientOrderId: ClientOrderId,
        val expectedAmount: BigDecimal,
    )

    /**
     * 결제 승인을 주문에 반영한다. `OrderCommandFacade.markPaid`의 입력이고 `PaymentApproved`의 값을 그대로 옮긴다.
     */
    data class MarkPaid(
        val orderId: OrderId,
        val checkoutNo: Int,
        val amount: Money,
    ) {
        data class Result(
            val orderId: OrderId,
            val status: OrderStatus,
        )
    }
}
