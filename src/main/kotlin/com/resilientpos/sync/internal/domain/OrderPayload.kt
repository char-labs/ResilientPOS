package com.resilientpos.sync.internal.domain

import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.Currency

/**
 * `CREATE_ORDER` operation이 실어 온 주문 내용. 단말이 보낸 값을 다듬지 않고 그대로 든다(단가는 raw `BigDecimal`).
 * `order` 명령으로 바꾸는 일([toCommand])은 항목 트랜잭션 안에서 한다 — `Money.of`의 거절(자릿수·범위·통화)이 배치 전체의 400이
 * 아니라 그 항목의 `REJECTED`가 되어야 단말이 그 항목을 버리고 나아간다.
 */
data class OrderPayload(
    val storeId: StoreId,
    val clientOrderId: ClientOrderId,
    val currency: Currency,
    val lines: List<Line>,
) : OperationPayload {
    override val kind = SyncOperationKind.CREATE_ORDER

    data class Line(
        val productCode: String,
        val productName: String,
        val unitPrice: BigDecimal,
        val quantity: Int,
    )

    /** `order` 공개 명령으로 바꾼다. 금액 규칙에 어긋나면 `DomainException`이다. */
    fun toCommand(terminalId: TerminalId): OrderCommand.Create = OrderCommand.Create(
        storeId = storeId,
        terminalId = terminalId,
        clientOrderId = clientOrderId,
        currency = currency,
        lines = lines.map { OrderCommand.Create.Line(it.productCode, it.productName, Money.of(it.unitPrice, currency), it.quantity) },
    )

    /**
     * 재전송 판정에 쓰는 SHA-256 hex. 종류 이름을 앞에 두고 필드마다 `길이:값;`으로 잇는다 — 길이 접두어라 값 안의 구분자가 경계를
     * 섞지 않고, 종류 접두어라 둘째 종류가 생겨도 기존 hash가 그대로다. 단가는 raw 표기 그대로라 `Money`의 표현과 무관하다.
     * 이 공식은 `t_sync_operation.payload_hash`의 저장 형식이다. 바꾸면 확정된 operation의 재전송이 모두 conflict가 되므로
     * 형식 버전 없이 바꾸지 않는다. line 순서가 다르면 다른 hash다. 단말은 저장한 봉투를 그대로 다시 보낸다.
     */
    override fun hash(): String {
        val fields = buildList {
            add(kind.name)
            add(storeId.value.toString())
            add(clientOrderId.value.toString())
            add(currency.currencyCode)
            lines.forEach { line ->
                add(line.productCode)
                add(line.productName)
                add(line.unitPrice.toPlainString())
                add(line.quantity.toString())
            }
        }
        return lengthPrefixedSha256(fields)
    }
}
