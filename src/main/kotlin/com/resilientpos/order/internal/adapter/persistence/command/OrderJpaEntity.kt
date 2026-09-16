package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.Currency
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * `Order` aggregate의 JPA 매핑. `data class`가 아니고 JPA가 관리하는 필드는 `var`다(도메인 규칙 §5).
 * 변환 `from`·`apply`·`toDomain`을 이 엔티티가 소유한다.
 */
@Entity
@Table(name = "t_order")
class OrderJpaEntity(
    @Id
    var id: UUID = UUID(0, 0),
    var storeId: UUID = UUID(0, 0),
    var terminalId: UUID = UUID(0, 0),
    var clientOrderId: UUID = UUID(0, 0),
    @Column(columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    var currency: String = "",
    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.OPEN,
    @Version
    var version: Long = 0,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "t_order_line", joinColumns = [JoinColumn(name = "order_id")])
    @OrderColumn(name = "line_no")
    var lines: MutableList<OrderLineJpaValue> = mutableListOf(),
) {

    /** 관리 엔티티에 최신 aggregate 상태를 반영한다. 이 PR에서 실행되는 경로는 없다(신규 저장만 발생). */
    fun apply(order: Order) {
        storeId = order.storeId.value
        terminalId = order.terminalId.value
        clientOrderId = order.clientOrderId.value
        currency = order.currency.currencyCode
        status = order.status
        lines.clear()
        lines.addAll(order.lines.map { OrderLineJpaValue.from(it) })
    }

    fun toDomain(): Order {
        val resolvedCurrency = Currency.getInstance(currency)
        return Order.restore(
            id = OrderId(id),
            storeId = StoreId(storeId),
            terminalId = TerminalId(terminalId),
            clientOrderId = ClientOrderId(clientOrderId),
            currency = resolvedCurrency,
            status = status,
            lines = lines.map { it.toDomain(resolvedCurrency) },
        )
    }

    companion object {
        fun from(order: Order): OrderJpaEntity = OrderJpaEntity(
            id = order.id.value,
            storeId = order.storeId.value,
            terminalId = order.terminalId.value,
            clientOrderId = order.clientOrderId.value,
            currency = order.currency.currencyCode,
            status = order.status,
            lines = order.lines.map { OrderLineJpaValue.from(it) }.toMutableList(),
        )
    }
}
