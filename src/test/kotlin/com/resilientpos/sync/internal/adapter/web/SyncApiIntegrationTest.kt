package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/**
 * `POST /v1/terminals/{terminalId}/sync`가 실제 Facade와 PostgreSQL까지 연결되는지 검사한다. Docker가 필요하다.
 */
class SyncApiIntegrationTest(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    terminals: TerminalManagement,
) : PostgresIntegrationTest() {

    /** 실제로 등록한 단말. 요청의 토큰, 경로의 terminalId, CREATE_ORDER payload의 storeId가 모두 이 단말이다. */
    private val terminal = terminals.registerTerminal()
    private val terminalId = terminal.terminalId.value
    private val storeId = terminal.storeId.value

    @Test
    fun `같은 배치를 두 번 보내면 첫 응답은 APPLIED 둘째는 DUPLICATE이고 orderId가 같으며 주문 행은 하나다`() {
        val clientOrderId = UUID.randomUUID()
        val body = """
            { "operations": [ {
                "operationId": "${UUID.randomUUID()}", "sequence": 1, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "$clientOrderId", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] }
            } ] }
        """.trimIndent()

        val first = objectMapper.readTree(post(body))
        val second = objectMapper.readTree(post(body))

        assertEquals("APPLIED", first.at("/data/items/0/status").asString())
        assertEquals("DUPLICATE", second.at("/data/items/0/status").asString())
        assertEquals(first.at("/data/items/0/orderId").asString(), second.at("/data/items/0/orderId").asString())
        assertEquals(1, second.at("/data/lastAckedSequence").asInt())
        assertEquals(1L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", clientOrderId))
    }

    @Test
    fun `주문 생성 다음 순번의 결제 요청을 HTTP로 보내면 둘 다 APPLIED이고 같은 주문이 CHECKOUT_REQUESTED다`() {
        val clientOrderId = UUID.randomUUID()
        val createOperationId = UUID.randomUUID()
        val checkoutOperationId = UUID.randomUUID()
        // 합계 3502원은 Fake 게이트웨이가 미확인으로 남겨 비동기 PAID 전이와 경합하지 않는 값이다.
        val body = """
            { "operations": [
              { "operationId": "$createOperationId", "sequence": 1, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "$clientOrderId", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "1751", "quantity": 2 }] } },
              { "operationId": "$checkoutOperationId", "sequence": 2, "kind": "REQUEST_CHECKOUT", "clientOrderId": "$clientOrderId", "amount": "3502" }
            ] }
        """.trimIndent()

        val response = objectMapper.readTree(post(body))

        assertEquals("APPLIED", response.at("/data/items/0/status").asString())
        assertEquals("APPLIED", response.at("/data/items/1/status").asString())
        assertEquals(response.at("/data/items/0/orderId").asString(), response.at("/data/items/1/orderId").asString())
        assertEquals(2, response.at("/data/lastAckedSequence").asInt())
        assertEquals("CHECKOUT_REQUESTED", jdbcTemplate.queryForObject<String>("select status from t_order where client_order_id = ?", clientOrderId))
        assertEquals("CREATE_ORDER", jdbcTemplate.queryForObject<String>("select kind from t_sync_operation where operation_id = ?", createOperationId))
        assertEquals("REQUEST_CHECKOUT", jdbcTemplate.queryForObject<String>("select kind from t_sync_operation where operation_id = ?", checkoutOperationId))
    }

    @Test
    fun `모르는 통화와 Int 범위를 넘는 수량의 항목은 배치 400이 아니라 각각 REJECTED이고 정상 항목은 적용된다`() {
        val body = """
            { "operations": [
              { "operationId": "${UUID.randomUUID()}", "sequence": 1, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "${UUID.randomUUID()}", "currency": "ZZZ",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] } },
              { "operationId": "${UUID.randomUUID()}", "sequence": 2, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 4294967297 }] } },
              { "operationId": "${UUID.randomUUID()}", "sequence": 3, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] } }
            ] }
        """.trimIndent()

        val response = objectMapper.readTree(post(body))

        assertEquals("REJECTED", response.at("/data/items/0/status").asString())
        assertEquals("UNSUPPORTED_CURRENCY", response.at("/data/items/0/errorCode").asString())
        assertEquals("REJECTED", response.at("/data/items/1/status").asString())
        assertEquals("INVALID_QUANTITY", response.at("/data/items/1/errorCode").asString())
        assertEquals("APPLIED", response.at("/data/items/2/status").asString())
        assertEquals(3, response.at("/data/lastAckedSequence").asInt())
    }

    @Test
    fun `소수 수량의 항목은 잘려서 적용되지 않고 배치 400도 아니라 REJECTED이며 다음 항목은 적용된다`() {
        val fractionalClientOrderId = UUID.randomUUID()
        val body = """
            { "operations": [
              { "operationId": "${UUID.randomUUID()}", "sequence": 1, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "$fractionalClientOrderId", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 1.5 }] } },
              { "operationId": "${UUID.randomUUID()}", "sequence": 2, "kind": "CREATE_ORDER",
                "order": { "storeId": "$storeId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                           "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] } }
            ] }
        """.trimIndent()

        val response = objectMapper.readTree(post(body))

        assertEquals("REJECTED", response.at("/data/items/0/status").asString())
        assertEquals("INVALID_QUANTITY", response.at("/data/items/0/errorCode").asString())
        assertEquals("APPLIED", response.at("/data/items/1/status").asString())
        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", fractionalClientOrderId))
    }

    @Test
    fun `다른 단말의 경로로 보내면 403 TERMINAL_MISMATCH이고 watermark 행이 생기지 않는다`() {
        val otherTerminalId = UUID.randomUUID()
        val body = """{ "operations": [ {
            "operationId": "${UUID.randomUUID()}", "sequence": 1, "kind": "CREATE_ORDER",
            "order": { "storeId": "$storeId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                       "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] } } ] }"""

        val response = mockMvc.post("/v1/terminals/$otherTerminalId/sync") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", terminal.bearer)
            content = body
        }.andExpect { status { isForbidden() } }.andReturn().response.contentAsString

        assertEquals("TERMINAL_MISMATCH", objectMapper.readTree(response).at("/error/errorCode").asString())
        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_sync_watermark where terminal_id in (?, ?)", otherTerminalId, terminalId))
    }

    private fun post(body: String): String =
        mockMvc.post("/v1/terminals/$terminalId/sync") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", terminal.bearer)
            content = body
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
}
