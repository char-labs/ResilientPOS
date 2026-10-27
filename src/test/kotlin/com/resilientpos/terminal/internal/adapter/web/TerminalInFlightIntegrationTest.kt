package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper

/**
 * 실제 handler mapping·인터셉터·DB 행 잠금에서, 앞 요청이 handler 안에서 행 잠금을 기다리는 동안 온 같은 단말의 요청이 거절되는지 검사한다. Docker가 필요하다.
 * 테스트가 별도 커넥션으로 행을 잡고 있다가 풀므로 잡는 시간이 행 잠금 대기 한도(3초)보다 짧아야 한다.
 */
class TerminalInFlightIntegrationTest(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
    terminals: TerminalManagement,
) : PostgresIntegrationTest() {

    private val terminal = terminals.registerTerminal()

    private fun inFlightRejects() = meterRegistry.counter("inbound.rate.limited", "scope", "in_flight").count()

    @Test
    fun `v1의 GET pattern은 결제 시도 조회와 메뉴 조회로 고정되어 있다`() {
        val patterns = handlerMapping.handlerMethods.keys
            .filter { HttpMethod.GET.name() in it.methodsCondition.methods.map { m -> m.name } }
            .flatMap { it.patternValues }
            .filter { it.startsWith("/v1/") && !it.startsWith("/v1/test-interceptor") } // 같은 패키지의 인터셉터 테스트용 Controller는 제외한다
            .toSet()

        assertEquals(
            setOf("/v1/orders/{orderId}/payment-attempts", "/v1/menu"), // 메뉴 조회는 t_menu PK 한 행을 잠그지 않고 읽는다
            patterns,
            "새 /v1 GET은 행 잠금·Lock을 잡지 않는지 확인한 뒤 여기에 더한다(spec 결정 3)",
        )
    }

    @Test
    fun `같은 단말의 checkout이 주문 행 잠금을 기다리는 동안 온 checkout은 429 RATE_LIMITED이고 잠금이 풀리면 앞 요청은 200이며 다음 checkout은 통과한다`() {
        // 합계 9002원은 끝자리가 2라 FakePaymentGateway가 미확인으로 남겨 결제 반영 listener가 주문 행을 잡지 않는다.
        val orderId = createOrder()
        val rejectsBefore = inFlightRejects()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = RowLock("select id from t_order where id = ? for update", UUID.fromString(orderId)).use { lock ->
                val pending = executor.submit(Callable { checkout(orderId).andReturn().response.status })
                awaitLockWait(lock)

                checkout(orderId).andExpect {
                    status { isTooManyRequests() }
                    header { string("Retry-After", "1") }
                    jsonPath("$.error.errorCode") { value("RATE_LIMITED") }
                }
                assertEquals(rejectsBefore + 1, inFlightRejects(), "429가 동시 상한에서 오지 않았다")
                // 결제 시도 조회(GET)는 자리를 잡지 않으므로 같은 단말의 checkout이 잠금을 기다리는 동안에도 동시 상한에 걸리지 않는다.
                mockMvc.get("/v1/orders/$orderId/payment-attempts") { header("Authorization", terminal.bearer) }
                    .andExpect { status { isOk() } }
                lock.release()
                pending
            }

            assertEquals(200, first.get(WAIT_SECONDS, TimeUnit.SECONDS))
            checkout(orderId).andExpect { status { isOk() } }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `같은 단말의 sync가 watermark 행 잠금을 기다리는 동안 온 sync는 429이고 sync 밖 쓰기 요청은 201이며 잠금이 풀리면 앞 sync는 200이다`() {
        sync(1).andExpect { status { isOk() } }
        val rejectsBefore = inFlightRejects()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = RowLock("select terminal_id from t_sync_watermark where terminal_id = ? for update", terminal.terminalId.value).use { lock ->
                val pending = executor.submit(Callable { sync(2).andReturn().response.status })
                awaitLockWait(lock)

                sync(3).andExpect {
                    status { isTooManyRequests() }
                    header { string("Retry-After", "1") }
                    jsonPath("$.error.errorCode") { value("RATE_LIMITED") }
                }
                assertEquals(rejectsBefore + 1, inFlightRejects(), "429가 동시 상한에서 오지 않았다")
                createOrder() // sync 밖 쓰기 요청은 sync 묶음의 동시 상한에 걸리지 않고 201이다
                lock.release()
                pending
            }

            assertEquals(200, first.get(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createOrder(): String {
        val body = """
            { "storeId": "${terminal.storeId.value}", "terminalId": "${terminal.terminalId.value}", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
              "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4501", "quantity": 2 }] }
        """.trimIndent()
        val created = mockMvc.post("/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", terminal.bearer)
            content = body
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(created).at("/data/orderId").asString()
    }

    private fun checkout(orderId: String) = mockMvc.post("/v1/orders/$orderId/checkout") { header("Authorization", terminal.bearer) }

    /** 항목 하나짜리 배치. 합계 9002원 주문을 만들어 결제 listener가 주문 행을 잡지 않게 한다. */
    private fun sync(sequence: Int) = mockMvc.post("/v1/terminals/${terminal.terminalId.value}/sync") {
        contentType = MediaType.APPLICATION_JSON
        header("Authorization", terminal.bearer)
        content = """
            { "operations": [ { "operationId": "${UUID.randomUUID()}", "sequence": $sequence, "kind": "CREATE_ORDER",
              "order": { "storeId": "${terminal.storeId.value}", "clientOrderId": "${UUID.randomUUID()}", "currency": "KRW",
                         "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4501", "quantity": 2 }] } } ] }
        """.trimIndent()
    }

    /** [lock]을 쥔 세션에 막혀 아직 얻지 못한 잠금이 있는 것을 본다. 다른 테스트나 리스너의 잠금 대기는 세지 않는다. */
    private fun awaitLockWait(lock: RowLock) =
        awaitUntil("행 잠금을 기다리는 요청이 있다") {
            (jdbcTemplate.queryForObject<Long>(
                "select count(*) from pg_locks where not granted and ? = any(pg_blocking_pids(pid))",
                lock.pid,
            ) ?: 0L) > 0L
        }

    /** 별도 커넥션(autocommit 끔)으로 행을 잡는다. [release]나 [close]로 롤백하고 커넥션을 돌려준다. 실패해도 [use]가 닫는다. */
    private inner class RowLock(sql: String, id: UUID) : AutoCloseable {
        private val connection: Connection = dataSource.connection.apply {
            autoCommit = false
            try {
                prepareStatement(sql).use { it.setObject(1, id); it.executeQuery().use { rs -> check(rs.next()) { "잠글 행이 없다" } } }
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        /** 이 커넥션의 backend pid. */
        val pid: Int = connection.createStatement().use { st -> st.executeQuery("select pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) } }

        fun release() {
            if (connection.isClosed) return
            connection.rollback()
            connection.close()
        }

        override fun close() = release()
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
