package com.resilientpos.payment.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 결제 시도 조회·재결제 endpoint를 실제 HTTP부터 PostgreSQL·Kafka까지 태운다. Docker가 필요하다.
 *
 * 금액 끝자리 1은 [com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway]가 거절한다.
 * 같은 금액의 재결제도 다시 거절되므로 새 시도의 결말은 단언하지 않고 개수·순서·paymentId만 본다.
 */
@AutoConfigureMockMvc
class PaymentAttemptApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val terminals: TerminalManagement,
) : PostgresIntegrationTest() {

    private val terminal = terminals.registerTerminal()

    private val declinedLines = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4501", "quantity": 1 }"""

    /** 거절 금액 주문을 만들고 checkout한 뒤 orderId를 돌려준다. */
    private fun checkoutDeclinedOrder(): String {
        val body = """
            {
              "storeId": "${terminal.storeId.value}",
              "terminalId": "${terminal.terminalId.value}",
              "clientOrderId": "${UUID.randomUUID()}",
              "currency": "KRW",
              "lines": [$declinedLines]
            }
        """.trimIndent()
        val created = mockMvc.post("/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", terminal.bearer)
            content = body
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val orderId = objectMapper.readTree(created).at("/data/orderId").asString()
        mockMvc.post("/v1/orders/$orderId/checkout") { header("Authorization", terminal.bearer) }.andExpect { status { isOk() } }
        return orderId
    }

    private fun attempts(orderId: String, bearer: String): List<JsonNode> {
        val response = mockMvc.get("/v1/orders/$orderId/payment-attempts") { header("Authorization", bearer) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val data = objectMapper.readTree(response).at("/data")
        return List(data.size()) { data.get(it) }
    }

    private fun retry(paymentId: String, bearer: String) =
        mockMvc.post("/v1/payment-attempts/$paymentId/retry") { header("Authorization", bearer) }

    @Test
    fun `거절된 주문은 조회에서 DECLINED 시도를 보이고 재결제하면 같은 회차에 시도가 둘이 되며 재요청은 같은 paymentId다`() {
        val orderId = checkoutDeclinedOrder()
        awaitUntil("첫 시도가 DECLINED로 조회된다. 현재: ${attempts(orderId, terminal.bearer)}") {
            attempts(orderId, terminal.bearer).singleOrNull()?.get("status")?.asString() == "DECLINED"
        }
        val declined = attempts(orderId, terminal.bearer).single()
        assertEquals(1, declined.get("checkoutNo").asInt())
        assertEquals("4501", declined.get("amount").asString())
        val declinedId = declined.get("paymentId").asString()

        val first = retry(declinedId, terminal.bearer).andExpect { status { isOk() } }.andReturn().response.contentAsString
        val retriedId = objectMapper.readTree(first).at("/data/paymentId").asString()
        val second = retry(declinedId, terminal.bearer).andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertEquals(retriedId, objectMapper.readTree(second).at("/data/paymentId").asString())
        assertEquals(false, retriedId == declinedId)
        val listed = attempts(orderId, terminal.bearer)
        assertEquals(listOf(declinedId, retriedId), listed.map { it.get("paymentId").asString() })
        assertEquals(listOf(1, 1), listed.map { it.get("checkoutNo").asInt() })
        assertEquals("DECLINED", listed[0].get("status").asString())
    }

    @Test
    fun `다른 매장의 단말은 조회가 200 빈 배열이고 재결제는 404 PAYMENT_ATTEMPT_NOT_FOUND이며 시도가 늘지 않는다`() {
        val orderId = checkoutDeclinedOrder()
        awaitUntil("첫 시도가 DECLINED로 조회된다. 현재: ${attempts(orderId, terminal.bearer)}") {
            attempts(orderId, terminal.bearer).singleOrNull()?.get("status")?.asString() == "DECLINED"
        }
        val declinedId = attempts(orderId, terminal.bearer).single().get("paymentId").asString()
        val otherStore = terminals.registerTerminal()

        assertEquals(0, attempts(orderId, otherStore.bearer).size)
        retry(declinedId, otherStore.bearer).andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("PAYMENT_ATTEMPT_NOT_FOUND") }
        }

        assertEquals(listOf(declinedId), attempts(orderId, terminal.bearer).map { it.get("paymentId").asString() })
    }
}
