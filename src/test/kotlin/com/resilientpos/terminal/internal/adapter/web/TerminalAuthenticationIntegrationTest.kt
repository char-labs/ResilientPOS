package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.RegisteredTerminal
import com.resilientpos.terminal.TerminalCommand
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 인터셉터에서 던진 예외가 실제 advice를 거쳐 401 envelope가 되는지, 어떤 원인이든 같은 응답이고 쓰기가 일어나지 않는지 검사한다.
 * Docker가 필요하다. 요청 하나는 `POST /v1/orders`로 대표한다. 세 endpoint는 같은 인터셉터 경로를 지난다.
 */
@AutoConfigureMockMvc
class TerminalAuthenticationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val terminals: TerminalManagement,
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

    private fun assertUnauthenticatedAndNothingWritten(result: ResultActionsDsl) {
        result.andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
            jsonPath("$.error.message") { value("인증이 필요합니다.") }
        }
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
        assertUnauthenticatedAndNothingWritten(createOrder(terminals.registerTerminal(), "Bearer unknown-token"))
    }

    @Test
    fun `회전된 옛 토큰은 401이고 새 토큰은 통과한다`() {
        val terminal = terminals.registerTerminal()
        val rotated = terminals.rotateCredential(TerminalCommand.RotateCredential(terminal.terminalId))

        assertUnauthenticatedAndNothingWritten(createOrder(terminal, terminal.bearer))
        createOrder(terminal, "Bearer ${rotated.token}").andExpect { status { isCreated() } }
    }

    @Test
    fun `비활성 단말의 토큰은 401이고 주문이 생기지 않는다`() {
        val terminal = terminals.registerTerminal()
        terminals.deactivate(TerminalCommand.Deactivate(terminal.terminalId))

        assertUnauthenticatedAndNothingWritten(createOrder(terminal, terminal.bearer))
    }

    @Test
    fun `actuator health는 토큰 없이 200이다`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
