package com.resilientpos.order.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
        jdbcTemplate.queryForObject(
            "select count(*) from t_order where client_order_id = ?",
            Long::class.java,
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

    @Test
    fun `주문을 만든 뒤 checkout을 요청하면 200과 확정 금액을 받고 재요청과 생성 재시도도 같은 주문을 가리킨다`() {
        val clientOrderId = UUID.randomUUID()
        val createBody = requestJson(clientOrderId)
        val created = post(createBody).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val orderId = objectMapper.readTree(created).at("/data/orderId").asString()

        val first = mockMvc.post("/v1/orders/$orderId/checkout").andExpect {
            status { isOk() }
            jsonPath("$.data.orderId") { value(orderId) }
            jsonPath("$.data.status") { value("CHECKOUT_REQUESTED") }
            jsonPath("$.data.checkoutNo") { value(1) }
            jsonPath("$.data.amount") { value("9000") }
            jsonPath("$.data.currency") { value("KRW") }
        }.andReturn().response.contentAsString
        val second = mockMvc.post("/v1/orders/$orderId/checkout").andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertEquals(objectMapper.readTree(first), objectMapper.readTree(second))
        assertEquals(
            "CHECKOUT_REQUESTED",
            jdbcTemplate.queryForObject("select status from t_order where id = ?", String::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            1L,
            jdbcTemplate.queryForObject("select count(*) from t_order_checkout where order_id = ?", Long::class.java, UUID.fromString(orderId)),
        )

        post(createBody).andExpect {
            status { isCreated() }
            jsonPath("$.data.orderId") { value(orderId) }
            jsonPath("$.data.status") { value("CHECKOUT_REQUESTED") }
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
