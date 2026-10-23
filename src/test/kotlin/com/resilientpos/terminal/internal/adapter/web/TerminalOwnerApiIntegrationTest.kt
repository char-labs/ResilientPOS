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
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.post

/**
 * 실제 점주 세션·CSRF 체인, 단말 관리 API, 단말 인터셉터, 감사 로그를 한 흐름으로 잇는다. Docker가 필요하다.
 * 주문 생성(`POST /v1/orders`)이 단말 토큰이 유효한지의 대표다.
 */
@ExtendWith(OutputCaptureExtension::class)
class TerminalOwnerApiIntegrationTest(
    private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {

    private val owner: OwnerSession by lazy { mockMvc.logInNewOwner() }
    private val storeId: UUID by lazy { createStore(owner) }

    private fun createStore(session: OwnerSession): UUID = UUID.fromString(
        mockMvc.post("/owner/v1/stores") {
            asOwner(session)
            contentType = MediaType.APPLICATION_JSON
            content = """{ "name": "강남점" }"""
        }.andExpect { status { isCreated() } }.field("$.data.storeId"),
    )

    private fun ownerPost(url: String, body: String? = null, session: OwnerSession = owner): ResultActionsDsl = mockMvc.post(url) {
        asOwner(session)
        contentType = MediaType.APPLICATION_JSON
        body?.let { content = it }
    }

    private fun register(code: String, store: UUID = storeId, session: OwnerSession = owner): ResultActionsDsl =
        ownerPost("/owner/v1/stores/$store/terminals", """{ "terminalCode": "$code" }""", session)

    private fun ResultActionsDsl.field(path: String): String = JsonPath.read(andReturn().response.contentAsString, path)

    private fun createOrder(token: String, terminalId: String): ResultActionsDsl = mockMvc.post("/v1/orders") {
        contentType = MediaType.APPLICATION_JSON
        header("Authorization", "Bearer $token")
        content = """
            { "storeId": "$storeId", "terminalId": "$terminalId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
              "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] }
        """.trimIndent()
    }

    private fun MockMvcResultMatchersDsl.errorCode(code: String, status: HttpStatus) {
        status { isEqualTo(status.value()) }
        jsonPath("$.error.errorCode") { value(code) }
    }

    @Test
    fun `등록한 단말의 토큰으로 주문을 만들고 회전과 비활성화가 토큰에 반영되며 목록은 코드 순이고 감사 로그에 토큰이 없다`(output: CapturedOutput) {
        val otherStore = createStore(owner)
        val registered = register("POS-B").andExpect {
            status { isCreated() }
            jsonPath("$.data.terminalCode") { value("POS-B") }
        }
        val terminalId = registered.field("$.data.terminalId")
        val firstToken = registered.field("$.data.token")
        register("POS-A").andExpect { status { isCreated() } }
        register("POS-A", otherStore).andExpect { status { isCreated() } }
        createOrder(firstToken, terminalId).andExpect { status { isCreated() } }

        val rotated = ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/credential/rotate").andExpect { status { isOk() } }
        val secondToken = rotated.field("$.data.token")
        createOrder(firstToken, terminalId).andExpect { status { isUnauthorized() } }
        createOrder(secondToken, terminalId).andExpect { status { isCreated() } }

        repeat(2) {
            ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/deactivate").andExpect {
                status { isOk() }
                jsonPath("$.data.terminalId") { value(terminalId) }
                jsonPath("$.data.status") { value("INACTIVE") }
            }
        }
        createOrder(secondToken, terminalId).andExpect { status { isUnauthorized() } }
        ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/credential/rotate").andExpect {
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
        mockMvc.get("/owner/v1/stores/$otherStore/terminals") { asOwner(owner) }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].terminalCode") { value("POS-A") }
        }

        val audit = output.all.lines().filter { "action=TERMINAL_" in it }
        assertEquals(listOf("TERMINAL_REGISTERED", "TERMINAL_REGISTERED", "TERMINAL_REGISTERED", "TERMINAL_CREDENTIAL_ROTATED", "TERMINAL_DEACTIVATED", "TERMINAL_DEACTIVATED"),
            audit.map { Regex("action=(\\w+)").find(it)!!.groupValues[1] })
        assertTrue(audit.all { "ownerId=${owner.ownerId} storeId=" in it }, audit.toString())
        assertFalse(output.all.contains(owner.email), "이메일이 출력에 남았다")
        assertTrue(audit[0].contains("storeId=$storeId terminalId=$terminalId") && audit[0].contains("terminalCode=POS-B"), audit[0])
        assertTrue(audit[3].contains("storeId=$storeId terminalId=$terminalId"), audit[3])
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

    @Test
    fun `남의 매장 경로로 등록 목록 회전 비활성화를 부르면 404 STORE_NOT_FOUND이고 그 매장의 단말은 바뀌지 않는다`(output: CapturedOutput) {
        val intruder = mockMvc.logInNewOwner()
        val registered = register("POS-1")
        val terminalId = registered.field("$.data.terminalId")
        val token = registered.field("$.data.token")

        register("POS-2", storeId, intruder).andExpect { errorCode("STORE_NOT_FOUND", HttpStatus.NOT_FOUND) }
        mockMvc.get("/owner/v1/stores/$storeId/terminals") { asOwner(intruder) }.andExpect { errorCode("STORE_NOT_FOUND", HttpStatus.NOT_FOUND) }
        ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/credential/rotate", session = intruder)
            .andExpect { errorCode("STORE_NOT_FOUND", HttpStatus.NOT_FOUND) }
        ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/deactivate", session = intruder)
            .andExpect { errorCode("STORE_NOT_FOUND", HttpStatus.NOT_FOUND) }
        register("POS-3", UUID.randomUUID()).andExpect { errorCode("STORE_NOT_FOUND", HttpStatus.NOT_FOUND) }

        createOrder(token, terminalId).andExpect { status { isCreated() } }
        mockMvc.get("/owner/v1/stores/$storeId/terminals") { asOwner(owner) }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].status") { value("ACTIVE") }
        }
        assertEquals(listOf("TERMINAL_REGISTERED"), output.all.lines().filter { "action=TERMINAL_" in it }.map { Regex("action=(\\w+)").find(it)!!.groupValues[1] })
    }

    @Test
    fun `내 매장 경로에 다른 매장의 단말 ID를 쓰면 404 TERMINAL_NOT_FOUND이고 그 단말은 바뀌지 않는다`() {
        val otherStore = createStore(owner)
        val registered = register("POS-1", otherStore)
        val terminalId = registered.field("$.data.terminalId")
        val token = registered.field("$.data.token")

        ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/credential/rotate").andExpect { errorCode("TERMINAL_NOT_FOUND", HttpStatus.NOT_FOUND) }
        ownerPost("/owner/v1/stores/$storeId/terminals/$terminalId/deactivate").andExpect { errorCode("TERMINAL_NOT_FOUND", HttpStatus.NOT_FOUND) }

        mockMvc.post("/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """
                { "storeId": "$otherStore", "terminalId": "$terminalId", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                  "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
        mockMvc.get("/owner/v1/stores/$otherStore/terminals") { asOwner(owner) }.andExpect { jsonPath("$.data[0].status") { value("ACTIVE") } }
    }
}
