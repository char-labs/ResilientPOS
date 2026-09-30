package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
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
 * `POST /v1/terminals/{terminalId}/sync`가 실제 Facade와 PostgreSQL까지 연결되는지 검사한다. Docker가 필요하다.
 */
@AutoConfigureMockMvc
class SyncApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : PostgresIntegrationTest() {

    @Test
    fun `같은 배치를 두 번 보내면 첫 응답은 APPLIED 둘째는 DUPLICATE이고 orderId가 같으며 주문 행은 하나다`() {
        val terminalId = UUID.randomUUID()
        val clientOrderId = UUID.randomUUID()
        val body = """
            { "operations": [ {
                "operationId": "${UUID.randomUUID()}", "sequence": 1, "kind": "CREATE_ORDER",
                "order": { "storeId": "${UUID.randomUUID()}", "clientOrderId": "$clientOrderId", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] }
            } ] }
        """.trimIndent()

        val first = objectMapper.readTree(post(terminalId, body))
        val second = objectMapper.readTree(post(terminalId, body))

        assertEquals("APPLIED", first.at("/data/items/0/status").asString())
        assertEquals("DUPLICATE", second.at("/data/items/0/status").asString())
        assertEquals(first.at("/data/items/0/orderId").asString(), second.at("/data/items/0/orderId").asString())
        assertEquals(1, second.at("/data/lastAckedSequence").asInt())
        assertEquals(1L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", clientOrderId))
    }

    @Test
    fun `주문 생성 다음 순번의 결제 요청을 HTTP로 보내면 둘 다 APPLIED이고 같은 주문이 CHECKOUT_REQUESTED다`() {
        val terminalId = UUID.randomUUID()
        val clientOrderId = UUID.randomUUID()
        val createOperationId = UUID.randomUUID()
        val checkoutOperationId = UUID.randomUUID()
        // 합계 3502원은 Fake 게이트웨이가 미확인으로 남겨 비동기 PAID 전이와 경합하지 않는 값이다.
        val body = """
            { "operations": [
              { "operationId": "$createOperationId", "sequence": 1, "kind": "CREATE_ORDER",
                "order": { "storeId": "${UUID.randomUUID()}", "clientOrderId": "$clientOrderId", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "1751", "quantity": 2 }] } },
              { "operationId": "$checkoutOperationId", "sequence": 2, "kind": "REQUEST_CHECKOUT", "clientOrderId": "$clientOrderId" }
            ] }
        """.trimIndent()

        val response = objectMapper.readTree(post(terminalId, body))

        assertEquals("APPLIED", response.at("/data/items/0/status").asString())
        assertEquals("APPLIED", response.at("/data/items/1/status").asString())
        assertEquals(response.at("/data/items/0/orderId").asString(), response.at("/data/items/1/orderId").asString())
        assertEquals(2, response.at("/data/lastAckedSequence").asInt())
        assertEquals("CHECKOUT_REQUESTED", jdbcTemplate.queryForObject<String>("select status from t_order where client_order_id = ?", clientOrderId))
        assertEquals("CREATE_ORDER", jdbcTemplate.queryForObject<String>("select kind from t_sync_operation where operation_id = ?", createOperationId))
        assertEquals("REQUEST_CHECKOUT", jdbcTemplate.queryForObject<String>("select kind from t_sync_operation where operation_id = ?", checkoutOperationId))
    }

    private fun post(terminalId: UUID, body: String): String =
        mockMvc.post("/v1/terminals/$terminalId/sync") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
}
