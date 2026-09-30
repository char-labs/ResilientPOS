package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.checkoutRowCount
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.order.orderStatus
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/**
 * `POST /v1/orders`가 실제 Facade와 PostgreSQL까지 연결되는지 검사한다. Docker가 필요하다.
 */
@AutoConfigureMockMvc
class OrderApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : PostgresIntegrationTest() {

    private fun orderRowCount(clientOrderId: UUID): Long =
        jdbcTemplate.queryForObject<Long>(
            "select count(*) from t_order where client_order_id = ?",
            clientOrderId,
        ) ?: 0L

    private fun post(body: String) = mockMvc.post("/v1/orders") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun requestJson(
        clientOrderId: UUID,
        lines: String = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }""",
        storeId: UUID = UUID.randomUUID(),
        terminalId: UUID = UUID.randomUUID(),
    ) = """
        {
          "storeId": "$storeId",
          "terminalId": "$terminalId",
          "clientOrderId": "$clientOrderId",
          "currency": "KRW",
          "lines": [$lines]
        }
    """.trimIndent()

    @Test
    fun `같은 본문을 두 번 보내면 두 번 다 201이고 orderId가 같으며 t_order에 행이 하나다`() {
        val clientOrderId = UUID.randomUUID()
        val body = requestJson(clientOrderId)

        val firstResponse = post(body).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val secondResponse = post(body).andExpect { status { isCreated() } }.andReturn().response.contentAsString

        val firstOrderId = objectMapper.readTree(firstResponse).at("/data/orderId").asString()
        val secondOrderId = objectMapper.readTree(secondResponse).at("/data/orderId").asString()

        assertEquals(firstOrderId, secondOrderId)
        assertEquals(1L, orderRowCount(clientOrderId))
    }

    @Test
    fun `line 상한과 같은 개수의 최대 길이 주문이 201로 저장된다`() {
        // 본문 크기 상한과 line 개수 상한이 서로 모순되지 않는지 본다. 여유가 두 배뿐이라
        // MAX_LINES를 올리거나 line DTO에 필드를 더하면 정상 주문이 전송 단계에서 끊긴다.
        // 그때 나가는 것은 TOO_MANY_ORDER_LINES가 아니라 이유를 알 수 없는 일반 400이다.
        val clientOrderId = UUID.randomUUID()
        val maxLine = """{ "productCode": "${"S".repeat(64)}", "productName": "${"가".repeat(255)}", "unitPrice": "999999999999", "quantity": 1 }"""
        val body = requestJson(clientOrderId, lines = List(Order.MAX_LINES) { maxLine }.joinToString(","))

        post(body).andExpect { status { isCreated() } }

        assertEquals(1L, orderRowCount(clientOrderId))
        assertEquals(
            Order.MAX_LINES.toLong(),
            jdbcTemplate.queryForObject<Long>(
                "select count(*) from t_order_line l join t_order o on o.id = l.order_id where o.client_order_id = ?",
                clientOrderId,
            ),
        )
    }

    @Test
    fun `line 상한을 하나 넘는 주문은 400 TOO_MANY_ORDER_LINES이고 행이 생기지 않는다`() {
        val clientOrderId = UUID.randomUUID()
        val line = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 1 }"""
        val body = requestJson(clientOrderId, lines = List(Order.MAX_LINES + 1) { line }.joinToString(","))

        post(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("TOO_MANY_ORDER_LINES") }
        }

        assertEquals(0L, orderRowCount(clientOrderId))
    }

    @Test
    fun `line이 빈 요청은 400 EMPTY_ORDER이고 t_order에 행이 생기지 않는다`() {
        val clientOrderId = UUID.randomUUID()
        val body = requestJson(clientOrderId, lines = "")

        post(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("EMPTY_ORDER") }
        }

        assertEquals(0L, orderRowCount(clientOrderId))
    }

    @Test
    fun `productCode가 빈 line은 400 BLANK_PRODUCT_CODE이고 t_order에 행이 생기지 않는다`() {
        val clientOrderId = UUID.randomUUID()
        val body = requestJson(
            clientOrderId,
            lines = """{ "productCode": "", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }""",
        )

        post(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("BLANK_PRODUCT_CODE") }
        }

        assertEquals(0L, orderRowCount(clientOrderId))
    }

    @Test
    fun `productName이 256자인 line은 400 PRODUCT_NAME_TOO_LONG이고 t_order에 행이 생기지 않는다`() {
        val clientOrderId = UUID.randomUUID()
        val body = requestJson(
            clientOrderId,
            lines = """{ "productCode": "SKU-1", "productName": "${"가".repeat(256)}", "unitPrice": "4500", "quantity": 2 }""",
        )

        post(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("PRODUCT_NAME_TOO_LONG") }
        }

        assertEquals(0L, orderRowCount(clientOrderId))
    }

    @Test
    fun `같은 storeId terminalId clientOrderId로 수량만 다른 요청을 이어서 보내면 두 번째는 409 IDEMPOTENCY_CONFLICT다`() {
        val storeId = UUID.randomUUID()
        val terminalId = UUID.randomUUID()
        val clientOrderId = UUID.randomUUID()
        val first = requestJson(clientOrderId, storeId = storeId, terminalId = terminalId)
        val second = requestJson(
            clientOrderId,
            lines = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 3 }""",
            storeId = storeId,
            terminalId = terminalId,
        )

        post(first).andExpect { status { isCreated() } }
        post(second).andExpect {
            status { isEqualTo(409) }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("IDEMPOTENCY_CONFLICT") }
        }

        assertEquals(1L, orderRowCount(clientOrderId))
    }

    /**
     * checkout 뒤 결제 리스너가 비동기로 돌아 주문이 PAID가 될 수 있다. 합계 9002원은 끝자리가 2라
     * FakePaymentGateway가 결과를 미확인으로 남겨, 주문이 CHECKOUT_REQUESTED에 머문다.
     */
    private val unsettledLines = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4501", "quantity": 2 }"""

    private fun createOrder(body: String): String {
        val created = post(body).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(created).at("/data/orderId").asString()
    }

    @Test
    fun `주문을 만든 뒤 checkout을 요청하면 200과 확정 금액을 받고 재요청도 같은 본문이다`() {
        val orderId = createOrder(requestJson(UUID.randomUUID(), lines = unsettledLines))

        val first = mockMvc.post("/v1/orders/$orderId/checkout").andExpect {
            status { isOk() }
            jsonPath("$.data.orderId") { value(orderId) }
            jsonPath("$.data.status") { value("CHECKOUT_REQUESTED") }
            jsonPath("$.data.checkoutNo") { value(1) }
            jsonPath("$.data.amount") { value("9002") }
            jsonPath("$.data.currency") { value("KRW") }
        }.andReturn().response.contentAsString
        val second = mockMvc.post("/v1/orders/$orderId/checkout").andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertEquals(objectMapper.readTree(first), objectMapper.readTree(second))
        assertEquals(
            "CHECKOUT_REQUESTED",
            jdbcTemplate.orderStatus(UUID.fromString(orderId)),
        )
        assertEquals(
            1L,
            jdbcTemplate.checkoutRowCount(UUID.fromString(orderId)),
        )
    }

    @Test
    fun `checkout을 요청한 주문의 생성 재시도는 201과 CHECKOUT_REQUESTED다`() {
        val createBody = requestJson(UUID.randomUUID(), lines = unsettledLines)
        val orderId = createOrder(createBody)
        mockMvc.post("/v1/orders/$orderId/checkout").andExpect { status { isOk() } }

        post(createBody).andExpect {
            status { isCreated() }
            jsonPath("$.data.orderId") { value(orderId) }
            jsonPath("$.data.status") { value("CHECKOUT_REQUESTED") }
        }
    }

    @Test
    fun `저장할 수 없는 금액의 주문은 400 INVALID_MONEY_AMOUNT이고 t_order에 행이 생기지 않는다`() {
        // 단가 자체가 범위를 넘는 경우
        val overLimit = UUID.randomUUID()
        post(
            requestJson(
                overLimit,
                lines = """{ "productCode": "SKU-1", "productName": "쌀", "unitPrice": "1000000000000000", "quantity": 1 }""",
            ),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("INVALID_MONEY_AMOUNT") }
        }
        assertEquals(0L, orderRowCount(overLimit))

        // 단가는 범위 안이지만 합계가 넘는 경우. 예전에는 201로 커밋된 뒤 checkout에서 500이 나고 복구할 수 없었다.
        val overTotal = UUID.randomUUID()
        post(
            requestJson(
                overTotal,
                lines = """{ "productCode": "SKU-1", "productName": "쌀", "unitPrice": "999999999999999", "quantity": 2 }""",
            ),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("INVALID_MONEY_AMOUNT") }
        }
        assertEquals(0L, orderRowCount(overTotal))
    }

    @Test
    fun `정수부 15자리 금액은 저장되고 checkout까지 그대로 돌아온다`() {
        val clientOrderId = UUID.randomUUID()
        val created = post(
            requestJson(
                clientOrderId,
                lines = """{ "productCode": "SKU-1", "productName": "쌀", "unitPrice": "999999999999999", "quantity": 1 }""",
            ),
        ).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val orderId = objectMapper.readTree(created).at("/data/orderId").asString()

        mockMvc.post("/v1/orders/$orderId/checkout").andExpect {
            status { isOk() }
            jsonPath("$.data.amount") { value("999999999999999") }
        }
    }

    @Test
    fun `없는 주문의 checkout 요청은 404 ORDER_NOT_FOUND다`() {
        mockMvc.post("/v1/orders/${UUID.randomUUID()}/checkout").andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("ORDER_NOT_FOUND") }
        }
    }
}
