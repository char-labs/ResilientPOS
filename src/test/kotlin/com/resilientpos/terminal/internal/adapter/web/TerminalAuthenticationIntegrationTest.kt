package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.RegisteredTerminal
import com.resilientpos.terminal.TerminalFixtures
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.post

/**
 * 인터셉터에서 던진 예외가 실제 advice를 거쳐 401 envelope가 되는지, 어떤 원인이든 같은 응답이고 쓰기가 일어나지 않는지 검사한다.
 * Docker가 필요하다. 요청 하나는 `POST /v1/orders`로 대표한다. 세 endpoint는 같은 인터셉터 경로를 지난다.
 */
@ExtendWith(OutputCaptureExtension::class)
class TerminalAuthenticationIntegrationTest(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
    private val terminals: TerminalManagement,
) : PostgresIntegrationTest() {

    private val clientOrderId = UUID.randomUUID()

    private fun createOrder(terminal: RegisteredTerminal, authorization: String?): ResultActionsDsl = mockMvc.post("/v1/orders") {
        contentType = MediaType.APPLICATION_JSON
        authorization?.let { header("Authorization", it) }
        content = """
            { "storeId": "${terminal.storeId.value}", "terminalId": "${terminal.terminalId.value}", "clientOrderId": "$clientOrderId", "currency": "KRW",
              "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] }
        """.trimIndent()
    }

    private fun MockMvcResultMatchersDsl.unauthenticatedEnvelope() {
        status { isUnauthorized() }
        header { string("WWW-Authenticate", "Bearer") }
        jsonPath("$.result") { value("FAIL") }
        jsonPath("$.data") { value(null) }
        jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        jsonPath("$.error.message") { value("인증이 필요합니다.") }
    }

    private fun assertUnauthenticatedAndNothingWritten(result: ResultActionsDsl) {
        result.andExpect { unauthenticatedEnvelope() }
        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", clientOrderId))
    }

    @Test
    fun `유효한 토큰이면 요청이 통과해 주문이 만들어진다`() {
        val terminal = terminals.registerTerminal()

        createOrder(terminal, terminal.bearer).andExpect { status { isCreated() } }

        assertEquals(1L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", clientOrderId))
    }

    @Test
    fun `헤더가 없으면 401이고 주문이 생기지 않는다`() {
        assertUnauthenticatedAndNothingWritten(createOrder(terminals.registerTerminal(), null))
    }

    @Test
    fun `Bearer 형식이 아니면 401이고 주문이 생기지 않는다`() {
        val terminal = terminals.registerTerminal()

        assertUnauthenticatedAndNothingWritten(createOrder(terminal, "Basic ${terminal.token}"))
    }

    @Test
    fun `모르는 토큰은 401이고 주문이 생기지 않는다`() {
        assertUnauthenticatedAndNothingWritten(createOrder(terminals.registerTerminal(), "Bearer ${UNKNOWN_TOKEN}"))
    }

    @Test
    fun `형식이 토큰과 다른 값은 401이고 주문이 생기지 않는다`() {
        val terminal = terminals.registerTerminal()

        for (authorization in listOf("Bearer", "Bearer ", "Bearer unknown-token", "Bearer ${terminal.token}x")) {
            assertUnauthenticatedAndNothingWritten(createOrder(terminal, authorization))
        }
    }

    @Test
    fun `소문자 bearer 스킴도 유효한 토큰이면 통과한다`() {
        val terminal = terminals.registerTerminal()

        createOrder(terminal, "bearer ${terminal.token}").andExpect { status { isCreated() } }
    }

    @Test
    fun `토큰은 401 요청과 성공 요청 어디서도 출력에 남지 않고 401은 로그에 남는다`(output: CapturedOutput) {
        val terminal = terminals.registerTerminal()

        createOrder(terminal, "Bearer $UNKNOWN_TOKEN").andExpect { status { isUnauthorized() } }
        createOrder(terminal, terminal.bearer).andExpect { status { isCreated() } }

        assertTrue(output.all.contains("errorCode=UNAUTHENTICATED"), "401 로그가 출력에 없어 부재 검사가 무의미하다")
        assertFalse(output.all.contains(UNKNOWN_TOKEN), "모르는 토큰이 출력에 남았다")
        assertFalse(output.all.contains(terminal.token), "유효 토큰이 출력에 남았다")
    }

    @Test
    fun `checkout은 헤더가 없으면 401이고 checkout 행이 생기지 않는다`() {
        val terminal = terminals.registerTerminal()
        createOrder(terminal, terminal.bearer).andExpect { status { isCreated() } }
        val orderId = jdbcTemplate.queryForObject<UUID>("select id from t_order where client_order_id = ?", clientOrderId)

        mockMvc.post("/v1/orders/{orderId}/checkout", orderId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "amount": "9000" }"""
        }.andExpect { unauthenticatedEnvelope() }

        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order_checkout where order_id = ?", orderId))
    }

    @Test
    fun `sync는 헤더가 없으면 401이고 watermark와 operation 행이 생기지 않는다`() {
        val terminal = terminals.registerTerminal()

        mockMvc.post("/v1/terminals/{terminalId}/sync", terminal.terminalId.value) {
            contentType = MediaType.APPLICATION_JSON
            content = """
                { "operations": [{ "operationId": "${UUID.randomUUID()}", "sequence": 1, "kind": "CREATE_ORDER",
                  "order": { "storeId": "${terminal.storeId.value}", "clientOrderId": "$clientOrderId", "currency": "KRW",
                    "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }] } }] }
            """.trimIndent()
        }.andExpect { unauthenticatedEnvelope() }

        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_sync_operation where terminal_id = ?", terminal.terminalId.value))
        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_sync_watermark where terminal_id = ?", terminal.terminalId.value))
        assertEquals(0L, jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", clientOrderId))
    }

    @Test
    fun `회전된 옛 토큰은 401이고 새 토큰은 통과한다`() {
        val terminal = terminals.registerTerminal()
        val rotated = terminals.rotateCredential(TerminalFixtures.rotateCredentialCommand(terminal.storeId, terminal.terminalId))

        assertUnauthenticatedAndNothingWritten(createOrder(terminal, terminal.bearer))
        createOrder(terminal, "Bearer ${rotated.token}").andExpect { status { isCreated() } }
    }

    @Test
    fun `비활성 단말의 토큰은 401이고 주문이 생기지 않는다`() {
        val terminal = terminals.registerTerminal()
        terminals.deactivate(TerminalFixtures.deactivateCommand(terminal.storeId, terminal.terminalId))

        assertUnauthenticatedAndNothingWritten(createOrder(terminal, terminal.bearer))
    }

    @Test
    fun `actuator health는 토큰 없이 200이다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    private companion object {
        /** 형식은 유효(base64url 43자)하지만 발급한 적 없는 토큰. 형식 검사가 아니라 DB 조회에서 거절되는 경로를 탄다. */
        const val UNKNOWN_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
