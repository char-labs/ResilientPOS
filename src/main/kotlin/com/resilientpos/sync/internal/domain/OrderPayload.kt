package com.resilientpos.sync.internal.domain

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.Currency

/**
 * `CREATE_ORDER` operation이 실어 온 주문 내용. 단말이 보낸 값을 다듬지 않고 그대로 든다(통화는 raw 문자열, 수량과 단가는 raw `BigDecimal`).
 * `order` 명령으로 바꾸는 일([toCommand])은 항목 트랜잭션 안에서 한다 — `Money.of`의 거절(자릿수·범위)과 통화 코드·수량(정수 아님, Int 범위 밖)의 거절이 배치 전체의 400이
 * 아니라 그 항목의 `REJECTED`가 되어야 단말이 그 항목을 버리고 나아간다.
 */
data class OrderPayload(
    val storeId: StoreId,
    val clientOrderId: ClientOrderId,
    val currency: String,
    val lines: List<Line>,
) : OperationPayload {
    override val kind = SyncOperationKind.CREATE_ORDER

    data class Line(
        val productCode: String,
        val productName: String,
        val unitPrice: BigDecimal,
        val quantity: BigDecimal,
    )

    companion object {
        /** `order`의 code와 같은 이름이다. 단말이 같은 거절로 읽게 하려는 것이고 같다는 것은 테스트가 지킨다. */
        const val INVALID_QUANTITY = "INVALID_QUANTITY"
    }

    /** `order` 공개 명령으로 바꾼다. 통화·수량·금액 규칙에 어긋나면 `DomainException`이다. */
    fun toCommand(terminalId: TerminalId): OrderCommand.Create {
        // Currency.getInstance의 거절은 IllegalArgumentException이다. 그것만 거절로 바꾸고 Error나 다른 예외는 그대로 올려 배치 오류가 되게 한다.
        val currency = try {
            Currency.getInstance(currency)
        } catch (_: IllegalArgumentException) {
            throw DomainException(Money.UNSUPPORTED_CURRENCY, "지원하지 않는 통화 코드입니다")
        }
        return OrderCommand.Create(
            storeId = storeId,
            terminalId = terminalId,
            clientOrderId = clientOrderId,
            currency = currency,
            lines = lines.map {
                OrderCommand.Create.Line(it.productCode, it.productName, Money.of(it.unitPrice, currency), it.quantity.toIntExact())
            },
        )
    }

    /** 소수부가 있거나 Int 범위 밖이면 `ArithmeticException`이다. 지수를 펴지 않고 판정하므로 `1E+999999999`도 바로 거절된다. */
    private fun BigDecimal.toIntExact(): Int = try {
        intValueExact()
    } catch (_: ArithmeticException) {
        throw DomainException(INVALID_QUANTITY, "수량은 정수여야 하고 허용 범위 안이어야 합니다")
    }

    /**
     * 재전송 판정에 쓰는 SHA-256 hex. 종류 이름을 앞에 두고 필드마다 `길이:값;`으로 잇는다 — 길이 접두어라 값 안의 구분자가 경계를
     * 섞지 않고, 종류 접두어라 둘째 종류가 생겨도 기존 hash가 그대로다. 통화·수량·단가는 raw 값 그대로라 `Currency`·`Money`의 표현과 무관하다(통화는 문자열, 수량은 십진 표기).
     * 수량도 단가처럼 표기에 민감하다. `2`와 `2.0`은 같은 주문이지만 다른 hash다.
     * 단가는 `toString()` 표기다. scale이 0 이상이고 조정 지수가 -6 이상인 값(`4500`, `4500.50`)은 `toPlainString()`과 같은 문자열이다.
     * 그 밖의 값(`1E+3`, `0.0000001`, 소수 0이 일곱 개 이상인 0)은 지수 표기로 들어간다. hash는 `Money`의 거절보다 먼저 도는데
     * `toPlainString()`은 큰 지수를 자릿수만큼 펴서 메모리를 다 쓰므로 쓰지 않는다(`CheckoutPayload`와 같다).
     * 이 공식은 `t_sync_operation.payload_hash`의 저장 형식이다. 바꾸면 확정된 operation의 재전송이 모두 conflict가 되므로
     * 형식 버전 없이 바꾸지 않는다(단가 표기를 `toString()`으로 바꾼 것은 기록된 행이 없던 때다). line 순서가 다르면 다른 hash다. 단말은 저장한 봉투를 그대로 다시 보낸다.
     */
    override fun hash(): String {
        val fields = buildList {
            add(kind.name)
            add(storeId.value.toString())
            add(clientOrderId.value.toString())
            add(currency)
            lines.forEach { line ->
                add(line.productCode)
                add(line.productName)
                add(line.unitPrice.toString())
                add(line.quantity.toString())
            }
        }
        return lengthPrefixedSha256(fields)
    }
}
