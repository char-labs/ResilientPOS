package com.resilientpos.order

import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.Currency

/**
 * 주문 생성 요청. `OrderCommandFacade.create`의 공개 입력이며 검증 로직을 갖지 않는다.
 */
data class CreateOrderCommand(
    val storeId: StoreId,
    val terminalId: TerminalId,
    val clientOrderId: ClientOrderId,
    val currency: Currency,
    val lines: List<CreateOrderLine>,
)

/**
 * 주문 생성 요청에 담긴 line 입력.
 */
data class CreateOrderLine(
    val productCode: String,
    val productName: String,
    val unitPrice: Money,
    val quantity: Int,
)

/**
 * 주문 생성 결과.
 */
data class CreateOrderResult(
    val orderId: OrderId,
    val status: OrderStatus,
)
