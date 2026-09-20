package com.resilientpos.order.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.Transition
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.OrderCreated
import com.resilientpos.order.OrderEvent
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.security.MessageDigest
import java.time.Instant
import java.util.Currency
import java.util.HexFormat

/**
 * 주문 aggregate 루트. line과 lifecycle을 소유하고 생성 불변식을 검증한다.
 */
class Order private constructor(
    val id: OrderId,
    val storeId: StoreId,
    val terminalId: TerminalId,
    val clientOrderId: ClientOrderId,
    val currency: Currency,
    val status: OrderStatus,
    val lines: List<OrderLine>,
    val payloadHash: String?,
) {

    val total: Money get() = lines.fold(Money.zero(currency)) { acc, line -> acc + line.amount }

    override fun equals(other: Any?) = other is Order && other.id == id
    override fun hashCode() = id.hashCode()

    /**
     * 같은 `(terminalId, clientOrderId)`로 다시 온 command가 최초 요청과 같은 내용인지 검증한다.
     * `payloadHash`가 null이면(V003 이전 행) 판정할 수 없으므로 통과시킨다.
     */
    fun verifySamePayload(command: CreateOrderCommand) {
        val hash = payloadHash ?: return
        if (hash != payloadHashOf(command)) {
            throw DomainException(
                IDEMPOTENCY_CONFLICT,
                "같은 clientOrderId로 내용이 다른 주문 생성 요청이 이미 처리되었습니다",
                DomainErrorKind.CONFLICT,
            )
        }
    }

    companion object {
        const val EMPTY_ORDER = "EMPTY_ORDER"
        const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
        const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"

        /**
         * 이미 저장된 주문을 그대로 재구성한다. 생성 불변식은 최초 `create` 때 검증됐으므로 다시 묻지 않는다.
         * persistence adapter의 `toDomain()`만 부른다.
         */
        fun restore(
            id: OrderId,
            storeId: StoreId,
            terminalId: TerminalId,
            clientOrderId: ClientOrderId,
            currency: Currency,
            status: OrderStatus,
            lines: List<OrderLine>,
            payloadHash: String?,
        ): Order = Order(id, storeId, terminalId, clientOrderId, currency, status, lines, payloadHash)

        fun create(command: CreateOrderCommand, ids: IdentifierGenerator, now: Instant): Transition<Order, OrderEvent> {
            if (command.lines.isEmpty()) {
                throw DomainException(EMPTY_ORDER, "주문에는 line이 1개 이상 필요합니다")
            }
            if (command.lines.any { it.unitPrice.currency != command.currency }) {
                throw DomainException(CURRENCY_MISMATCH, "line 통화가 주문 통화와 다릅니다")
            }

            val orderId = OrderId(ids.next())
            val order = Order(
                id = orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                clientOrderId = command.clientOrderId,
                currency = command.currency,
                status = OrderStatus.OPEN,
                lines = command.lines.map { OrderLine(OrderLineId(ids.next()), it.productCode, it.productName, it.unitPrice, it.quantity) },
                payloadHash = payloadHashOf(command),
            )
            val event = OrderCreated(
                eventId = ids.next(),
                occurredAt = now,
                orderId = orderId,
                storeId = command.storeId,
                terminalId = command.terminalId,
                clientOrderId = command.clientOrderId,
            )
            return Transition(order, listOf(event))
        }

        /**
         * 재시도 판정용 canonical 표현의 SHA-256 hash. `terminalId`·`clientOrderId`는 조회 키라서 뺀다.
         * 필드는 `길이:값`으로 이어 붙여 구분자 없는 이어붙임의 경계 충돌(`AB`+`C` vs `A`+`BC`)을 막는다.
         */
        private fun payloadHashOf(command: CreateOrderCommand): String {
            fun canonical(fields: List<String>): String = fields.joinToString("") { "${it.length}:$it" }

            val lineStrings = command.lines.map { line ->
                canonical(
                    listOf(
                        line.productCode,
                        line.productName,
                        line.unitPrice.amount.toPlainString(),
                        line.unitPrice.currency.currencyCode,
                        line.quantity.toString(),
                    ),
                )
            }.sorted()

            val payload = canonical(listOf(command.storeId.value.toString(), command.currency.currencyCode) + lineStrings)
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
