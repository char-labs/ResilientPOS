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
                "operationId": "${UUID.randomUUID()}", "sequence": 1,
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

    private fun post(terminalId: UUID, body: String): String =
        mockMvc.post("/v1/terminals/$terminalId/sync") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
}
