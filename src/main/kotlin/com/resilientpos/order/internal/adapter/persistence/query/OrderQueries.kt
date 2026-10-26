package com.resilientpos.order.internal.adapter.persistence.query

import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderDetailView
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderSummaryView
import com.resilientpos.order.internal.adapter.persistence.command.OrderCheckoutEntity
import com.resilientpos.order.internal.adapter.persistence.command.OrderEntity
import com.resilientpos.order.internal.adapter.persistence.command.OrderLineEntity
import com.resilientpos.platform.JdslQueryExecutor
import com.resilientpos.platform.ReadOnlyTransactional
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
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

    /**
     * 매장의 주문을 `occurred_at desc, id desc`로 한 페이지 읽는다. 쿼리는 하나다.
     * `total`은 select 절의 상관 subquery라 limit으로 자른 페이지의 행에만 계산한다.
     * line join + group by 뒤에 order by·limit을 걸면 PostgreSQL이 매장 이력 전체를 집계한 뒤 자른다(PR spec의 실측).
     * keyset 술어의 앞 조건(`occurred_at <= 시각`)은 중복이지만 인덱스의 범위 조건이 된다. OR만 쓰면 Filter가 되어 앞 행을 다시 읽는다.
     */
    @ReadOnlyTransactional
    fun findOrders(storeId: StoreId, criteria: OrderQuery.FindOrders.Criteria): OrderQuery.FindOrders.Result {
        val rows = queries.findAll(limit = criteria.size + 1) {
            val order = entity(OrderEntity::class)
            val line = entity(OrderLineEntity::class)
            val total = select<BigDecimal>(
                sum(
                    // 단가(BigDecimal)와 수량(Int)은 DSL의 times가 같은 타입만 받아 template으로 곱한다.
                    customExpression(BigDecimal::class, "{0} * {1}", path(OrderLineEntity::unitPrice), path(OrderLineEntity::quantity)),
                ),
            )
                .from(line)
                .where(path(OrderLineEntity::orderId).eq(path(OrderEntity::id)))
                .asSubquery()
            val after = criteria.after
            select(Any::class, order, total)
                .from(order)
                .where(
                    and(
                        path(OrderEntity::storeId).eq(storeId.value),
                        criteria.from?.let { path(OrderEntity::occurredAt).ge(it) },
                        criteria.to?.let { path(OrderEntity::occurredAt).lt(it) },
                        criteria.status?.let { path(OrderEntity::status).eq(it) },
                        after?.let {
                            and(
                                path(OrderEntity::occurredAt).le(it.occurredAt),
                                or(
                                    path(OrderEntity::occurredAt).lt(it.occurredAt),
                                    path(OrderEntity::id).lt(it.orderId.value),
                                ),
                            )
                        },
                    ),
                )
                .orderBy(path(OrderEntity::occurredAt).desc(), path(OrderEntity::id).desc())
        }
        val page = rows.take(criteria.size).map { row ->
            val columns = row as Array<*>
            val order = columns[0] as OrderEntity
            val currency = Currency.getInstance(order.currency)
            OrderSummaryView(
                orderId = OrderId(order.id),
                terminalId = TerminalId(order.terminalId),
                status = order.status,
                currency = currency,
                total = (columns[1] as BigDecimal?)?.let { Money.of(it, currency) } ?: Money.zero(currency),
                occurredAt = order.occurredAt,
            )
        }
        val next = if (rows.size > criteria.size) page.last().let { OrderQuery.FindOrders.Cursor(it.occurredAt, it.orderId) } else null
        return OrderQuery.FindOrders.Result(page, next)
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
