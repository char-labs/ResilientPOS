package com.resilientpos.order

import com.resilientpos.foundation.Money
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency

/** 주문 목록 한 줄의 읽기 투영. line은 싣지 않고 `total`(line 합)만 쿼리에서 계산해 싣는다. */
data class OrderSummaryView(
    val orderId: OrderId,
    val terminalId: TerminalId,
    val status: OrderStatus,
    val currency: Currency,
    val total: Money,
    val occurredAt: Instant,
)
