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

    private val amount = BigDecimal("3502")

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

        assertEquals(CheckoutPayload(clientOrderId, amount).hash(), CheckoutPayload(clientOrderId, amount).hash())
        assertNotEquals(CheckoutPayload(clientOrderId, amount).hash(), CheckoutPayload(other, amount).hash())
    }

    @Test
    fun `CheckoutPayload의 hash는 amount가 다르면 다르고 같은 금액도 표기가 다르면 다르다`() {
        val base = CheckoutPayload(clientOrderId, amount).hash()

        assertNotEquals(base, CheckoutPayload(clientOrderId, BigDecimal("3501")).hash())
        assertNotEquals(base, CheckoutPayload(clientOrderId, BigDecimal("3502.0")).hash())
    }

    @Test
    fun `CheckoutPayload의 hash는 종류·clientOrderId·amount를 길이 접두어로 이은 SHA-256 hex다`() {
        // python3 hashlib.sha256("16:REQUEST_CHECKOUT;36:00000000-0000-0000-0000-000000000002;4:3502;")로 따로 구한 값이다.
        assertEquals("3594856e434dc1721f93ff233276691bc229fd57c91a2c978e4743d7435bc6b3", CheckoutPayload(clientOrderId, amount).hash())
    }

    @Test
    fun `CheckoutPayload의 hash는 지수 표기 amount를 toString 그대로 넣는다`() {
        // toPlainString이면 필드가 4:1000;이 되어 이 값과 다르다. python3 hashlib.sha256("16:REQUEST_CHECKOUT;36:00000000-0000-0000-0000-000000000002;4:1E+3;")로 따로 구한 값이다.
        assertEquals("dd6c961e387c519a382cd653434ddb52c51ac5eed226733cab5a1631665c1fdf", CheckoutPayload(clientOrderId, BigDecimal("1E+3")).hash())
    }

    @Test
    fun `CheckoutPayload의 hash는 극단적인 지수의 amount도 지수를 펴지 않고 끝난다`() {
        // toPlainString이면 자릿수만큼 펴서 메모리를 다 쓴다.
        assertEquals(64, CheckoutPayload(clientOrderId, BigDecimal("1E+999999999")).hash().length)
        assertEquals(64, CheckoutPayload(clientOrderId, BigDecimal("1E-999999999")).hash().length)
    }

    @Test
    fun `같은 clientOrderId를 가진 OrderPayload와 CheckoutPayload의 hash는 다르다`() {
        assertNotEquals(order.hash(), CheckoutPayload(clientOrderId, amount).hash())
    }

    @Test
    fun `payload는 자기 종류를 돌려준다`() {
        assertEquals(SyncOperationKind.CREATE_ORDER, order.kind)
        assertEquals(SyncOperationKind.REQUEST_CHECKOUT, CheckoutPayload(clientOrderId, amount).kind)
    }
}
