package com.resilientpos.order.internal.adapter.persistence.command

import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.platform.BaseEntity
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.Currency
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * `Order` aggregate 루트의 JPA 매핑. `data class`가 아니고 JPA가 관리하는 필드는 `var`다(도메인 규칙 §5).
 * line은 연관관계로 들고 있지 않다. `OrderLineEntity`가 `orderId`로 가리키고 `OrderPersistenceAdapter`가 조립한다.
 */
@Entity
@Table(name = "t_order")
class OrderEntity(
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
) : BaseEntity() {

    /** 관리 엔티티에 최신 aggregate 상태를 반영한다. */
    fun apply(order: Order) {
        storeId = order.storeId.value
        terminalId = order.terminalId.value
        clientOrderId = order.clientOrderId.value
        currency = order.currency.currencyCode
        status = order.status
    }

    fun toDomain(lines: List<OrderLineEntity>): Order {
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
        fun from(order: Order): OrderEntity = OrderEntity(
            id = order.id.value,
            storeId = order.storeId.value,
            terminalId = order.terminalId.value,
            clientOrderId = order.clientOrderId.value,
            currency = order.currency.currencyCode,
            status = order.status,
        )
    }
}
