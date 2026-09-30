package com.resilientpos.sync.internal.domain

import com.resilientpos.order.ClientOrderId
import com.resilientpos.store.StoreId
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PayloadHashTest {

    private val clientOrderId = ClientOrderId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    private val order = OrderPayload(
        storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        clientOrderId = clientOrderId,
        currency = Currency.getInstance("KRW"),
        lines = listOf(OrderPayload.Line("SKU-1", "아메리카노", BigDecimal("1001"), 2)),
    )

    @Test
    fun `OrderPayload의 hash는 저장 형식이라 고정 입력의 기대 hex가 바뀌지 않는다`() {
        // 이 값은 t_sync_operation.payload_hash에 이미 저장된 형식이다. 기대값을 구현에 맞춰 바꾸지 않는다.
        assertEquals("d3e729322e5b43637c3b68457459f4b68ac51bda672ab4406c48c7450c81e9b9", order.hash())
    }

    @Test
    fun `CheckoutPayload의 hash는 같은 clientOrderId에 같고 다른 clientOrderId에 다르다`() {
        val other = ClientOrderId(UUID.fromString("00000000-0000-0000-0000-000000000003"))

        assertEquals(CheckoutPayload(clientOrderId).hash(), CheckoutPayload(clientOrderId).hash())
        assertNotEquals(CheckoutPayload(clientOrderId).hash(), CheckoutPayload(other).hash())
    }

    @Test
    fun `CheckoutPayload의 hash는 종류와 clientOrderId를 길이 접두어로 이은 SHA-256 hex다`() {
        assertEquals("791416dbf4adfa12700825a10494ed00f40765a3777d739a76d7a2ee989e7215", CheckoutPayload(clientOrderId).hash())
    }

    @Test
    fun `같은 clientOrderId를 가진 OrderPayload와 CheckoutPayload의 hash는 다르다`() {
        assertNotEquals(order.hash(), CheckoutPayload(clientOrderId).hash())
    }

    @Test
    fun `payload는 자기 종류를 돌려준다`() {
        assertEquals(SyncOperationKind.CREATE_ORDER, order.kind)
        assertEquals(SyncOperationKind.REQUEST_CHECKOUT, CheckoutPayload(clientOrderId).kind)
    }
}
