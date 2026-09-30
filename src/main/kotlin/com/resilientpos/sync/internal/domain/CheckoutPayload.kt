package com.resilientpos.sync.internal.domain

import com.resilientpos.order.ClientOrderId

/** `REQUEST_CHECKOUT` operation이 실어 온 것. 단말은 서버 `orderId`를 모르므로 `clientOrderId`로 주문을 가리킨다. */
data class CheckoutPayload(val clientOrderId: ClientOrderId) : OperationPayload {
    override val kind = SyncOperationKind.REQUEST_CHECKOUT

    override fun hash(): String = lengthPrefixedSha256(listOf(kind.name, clientOrderId.value.toString()))
}
