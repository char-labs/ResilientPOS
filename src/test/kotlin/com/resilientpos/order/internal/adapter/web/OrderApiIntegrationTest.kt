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
}
