package com.resilientpos.terminal.internal.adapter.web

import com.jayway.jsonpath.JsonPath
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.platform.OwnerSession
import com.resilientpos.platform.asOwner
import com.resilientpos.platform.logInNewOwner
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 실제 점주 세션·CSRF 체인, 단말 관리 API, 단말 인터셉터, 감사 로그를 한 흐름으로 잇는다. Docker가 필요하다.
 * 주문 생성(`POST /v1/orders`)이 단말 토큰이 유효한지의 대표다.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class TerminalAdminApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {

    private val owner: OwnerSession by lazy { mockMvc.logInNewOwner() }
    private val storeId = UUID.randomUUID()

    private fun adminPost(url: String, body: String? = null): ResultActionsDsl = mockMvc.post(url) {
        asOwner(owner)
        contentType = MediaType.APPLICATION_JSON
        body?.let { content = it }
    }

    private fun register(code: String, store: UUID = storeId): ResultActionsDsl =
        adminPost("/owner/v1/terminals", """{ "storeId": "$store", "terminalCode": "$code" }""")

    private fun ResultActionsDsl.field(path: String): String = JsonPath.read(andReturn().response.contentAsString, path)

    private fun createOrder(token: String, terminalId: String): ResultActionsDsl = mockMvc.post("/v1/orders") {
        contentType = MediaType.APPLICATION_JSON
        header("Authorization", "Bearer $token")
        content = """
            { "storeId": "$storeId", "terminalId": "$terminalId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
              "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] }
        """.trimIndent()
    }

    @Test
    fun `등록한 단말의 토큰으로 주문을 만들고 회전과 비활성화가 토큰에 반영되며 목록은 코드 순이고 감사 로그에 토큰이 없다`(output: CapturedOutput) {
        val registered = register("POS-B").andExpect {
            status { isCreated() }
            jsonPath("$.data.terminalCode") { value("POS-B") }
        }
        val terminalId = registered.field("$.data.terminalId")
        val firstToken = registered.field("$.data.token")
        register("POS-A").andExpect { status { isCreated() } }
        register("POS-A", UUID.randomUUID()).andExpect { status { isCreated() } }
        createOrder(firstToken, terminalId).andExpect { status { isCreated() } }

        val rotated = adminPost("/owner/v1/terminals/$terminalId/credential/rotate").andExpect { status { isOk() } }
        val secondToken = rotated.field("$.data.token")
        createOrder(firstToken, terminalId).andExpect { status { isUnauthorized() } }
        createOrder(secondToken, terminalId).andExpect { status { isCreated() } }

        repeat(2) {
            adminPost("/owner/v1/terminals/$terminalId/deactivate").andExpect {
                status { isOk() }
                jsonPath("$.data.terminalId") { value(terminalId) }
                jsonPath("$.data.status") { value("INACTIVE") }
            }
        }
        createOrder(secondToken, terminalId).andExpect { status { isUnauthorized() } }
        adminPost("/owner/v1/terminals/$terminalId/credential/rotate").andExpect {
            status { isConflict() }
            jsonPath("$.error.errorCode") { value("TERMINAL_INACTIVE") }
        }

        mockMvc.get("/owner/v1/stores/$storeId/terminals") { asOwner(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].terminalCode") { value("POS-A") }
            jsonPath("$.data[0].status") { value("ACTIVE") }
            jsonPath("$.data[1].terminalCode") { value("POS-B") }
            jsonPath("$.data[1].terminalId") { value(terminalId) }
            jsonPath("$.data[1].status") { value("INACTIVE") }
            jsonPath("$.data[1].token") { doesNotExist() }
        }

        val audit = output.all.lines().filter { "action=TERMINAL_" in it }
        assertEquals(listOf("TERMINAL_REGISTERED", "TERMINAL_REGISTERED", "TERMINAL_REGISTERED", "TERMINAL_CREDENTIAL_ROTATED", "TERMINAL_DEACTIVATED", "TERMINAL_DEACTIVATED"),
            audit.map { Regex("action=(\\w+)").find(it)!!.groupValues[1] })
        assertTrue(audit.all { "ownerId=${owner.ownerId}" in it }, audit.toString())
        assertFalse(output.all.contains(owner.email), "이메일이 출력에 남았다")
        assertTrue(audit[0].contains("terminalId=$terminalId") && audit[0].contains("storeId=$storeId") && audit[0].contains("terminalCode=POS-B"), audit[0])
        assertTrue(audit[3].contains("terminalId=$terminalId"), audit[3])
        assertTrue(audit[4].contains("before=ACTIVE") && audit[4].contains("after=INACTIVE"), audit[4])
        assertTrue(audit[5].contains("before=INACTIVE") && audit[5].contains("after=INACTIVE"), audit[5])
        assertFalse(output.all.contains(firstToken), "등록 토큰이 출력에 남았다")
        assertFalse(output.all.contains(secondToken), "회전 토큰이 출력에 남았다")
    }

    @Test
    fun `같은 매장 같은 코드는 409이고 형식이 틀린 코드는 400이며 감사 로그를 남기지 않는다`(output: CapturedOutput) {
        register("DUP-1").andExpect { status { isCreated() } }

        register("DUP-1").andExpect {
            status { isConflict() }
            jsonPath("$.error.errorCode") { value("TERMINAL_CODE_DUPLICATED") }
        }
        register("dup-2").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("INVALID_TERMINAL_CODE") }
        }

        assertEquals(1, output.all.lines().count { "action=TERMINAL_REGISTERED" in it && "terminalCode=DUP-1" in it })
        assertFalse(output.all.lines().any { "action=TERMINAL_REGISTERED" in it && "dup-2" in it })
    }
}
