package com.resilientpos.order.internal.adapter.persistence.query

import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderDetailView
import com.resilientpos.order.OrderId
import com.resilientpos.order.internal.adapter.persistence.command.OrderCheckoutEntity
import com.resilientpos.order.internal.adapter.persistence.command.OrderEntity
import com.resilientpos.order.internal.adapter.persistence.command.OrderLineEntity
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.Currency
import org.springframework.stereotype.Component

/**
 * 주문 조회 구현. aggregate와 `OrderRepository`를 거치지 않고 엔티티를 바로 View로 바꾼다.
 * 구현이 하나뿐이고 View는 aggregate 경계에 들어가지 않아서 port 인터페이스를 두지 않는다.
 */
@Component
class OrderQueries(
    private val queries: JdslQueryExecutor,
) {

    /**
     * 주문 하나를 매장 기준으로 읽는다. 쿼리는 둘이고 주문이 없으면 line을 읽지 않는다.
     * READ COMMITTED라 한 문장 = 한 스냅샷이다. 상태와 checkout은 한 쿼리(left join, 행 수는 회차 수)로 읽어 같은 커밋이다.
     * line은 생성 뒤 바뀌지 않아 따로 읽는다. line을 바꾸는 명령이 생기면 다시 본다.
     */
    @ReadOnlyTransactional
    fun getOrder(orderId: OrderId, storeId: StoreId): OrderDetailView? {
        val rows = queries.findAll {
            val order = entity(OrderEntity::class)
            val checkout = entity(OrderCheckoutEntity::class)
            select(Any::class, order, checkout)
                .from(order, leftJoin(checkout).on(path(OrderCheckoutEntity::orderId).eq(path(OrderEntity::id))))
                .where(path(OrderEntity::id).eq(orderId.value).and(path(OrderEntity::storeId).eq(storeId.value)))
                .orderBy(path(OrderCheckoutEntity::checkoutNo).asc())
        }
        val order = (rows.firstOrNull() as Array<*>?)?.get(0) as OrderEntity? ?: return null
        val checkouts = rows.mapNotNull { (it as Array<*>)[1] as OrderCheckoutEntity? }
        val lines = queries.findAll {
            val line = entity(OrderLineEntity::class)
            select(line).from(line)
                .where(path(OrderLineEntity::orderId).eq(orderId.value))
                .orderBy(path(OrderLineEntity::lineNo).asc())
        }
        return order.toView(lines, checkouts)
    }
}

private fun OrderEntity.toView(lines: List<OrderLineEntity>, checkouts: List<OrderCheckoutEntity>): OrderDetailView {
    val currency = Currency.getInstance(currency)
    val lineViews = lines.map {
        OrderDetailView.Line(it.productCode, it.productName, Money.of(it.unitPrice, currency), it.quantity)
    }
    return OrderDetailView(
        orderId = OrderId(id),
        clientOrderId = ClientOrderId(clientOrderId),
        terminalId = TerminalId(terminalId),
        status = status,
        version = checkNotNull(version),
        currency = currency,
        total = lineViews.fold(Money.zero(currency)) { sum, line -> sum + line.unitPrice * line.quantity },
        occurredAt = occurredAt,
        lines = lineViews,
        checkouts = checkouts.map { OrderDetailView.Checkout(it.checkoutNo, Money.of(it.amount, currency)) },
    )
}
