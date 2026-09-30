package com.resilientpos.sync.internal.domain

import com.resilientpos.order.ClientOrderId
import java.math.BigDecimal

/**
 * `REQUEST_CHECKOUT` operation이 실어 온 것. 단말은 서버 `orderId`를 모르므로 `clientOrderId`로 주문을 가리키고,
 * 어느 내용의 주문인지는 단말이 본 합계 `amount`로 밝힌다. `amount`는 단말이 보낸 raw `BigDecimal`이다.
 * scale이 다르면(`3502`와 `3502.0`) 같은 금액이라도 다른 hash다. `3502`와 `3.502E3`은 값과 scale이 같아 같은 hash다. 단말은 저장한 봉투를 그대로 다시 보낸다.
 * hash에는 `toPlainString()`이 아니라 `toString()`을 넣는다. hash는 `Money`의 거절보다 먼저 돌고, `toPlainString()`은 `1E+999999999` 같은
 * 지수 표기를 자릿수만큼 펴서 입력 하나로 메모리를 다 쓴다. `toString()`은 지수를 펴지 않아 길이가 지수 크기를 따라가지 않는다.
 */
data class CheckoutPayload(val clientOrderId: ClientOrderId, val amount: BigDecimal) : OperationPayload {
    override val kind = SyncOperationKind.REQUEST_CHECKOUT

    override fun hash(): String = lengthPrefixedSha256(listOf(kind.name, clientOrderId.value.toString(), amount.toString()))
}
