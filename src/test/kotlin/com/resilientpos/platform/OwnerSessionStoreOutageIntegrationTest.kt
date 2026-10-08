package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 세션 저장소(Redis)에 닿지 못하는 상태를 실제 HTTP로 검사한다. 공유 Redis 컨테이너를 멈추지 않고,
 * Redis 주소를 아무도 듣지 않는 포트로 둔 별도 컨텍스트를 쓴다(컨텍스트가 하나 더 뜬다. 받아들임).
 * `@ServiceConnection`이 `spring.data.redis.*`를 덮으므로 부모 클래스의 연결 정보 대신 같은 컨테이너의 접속 값을 속성으로 준다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilientpos.kafka.partitions=1",
        "resilientpos.kafka.retry.initial-interval=PT0.02S",
        "resilientpos.event-publication.resubmit-interval=P1D",
        "resilientpos.payment.unknown-resolve-interval=P1D",
    ],
)
class OwnerSessionStoreOutageIntegrationTest(
    @LocalServerPort private val port: Int,
    @Autowired private val terminals: TerminalManagement,
) {

    private val client = HttpClient.newHttpClient()

    private fun send(method: String, path: String, body: String? = null, cookie: String? = null, xsrf: String? = null, bearer: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .apply {
                if (body != null) header("Content-Type", "application/json")
                cookie?.let { header("Cookie", it) }
                xsrf?.let { header("X-XSRF-TOKEN", it) }
                bearer?.let { header("Authorization", it) }
            }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<String>.assertServiceUnavailableEnvelope() {
        assertEquals(503, statusCode(), body())
        assertTrue(headers().firstValue("Content-Type").orElse("").startsWith("application/json"), headers().toString())
        assertTrue(body().contains(""""errorCode":"SERVICE_UNAVAILABLE""""), body())
        assertTrue(body().contains(""""result":"FAIL""""), body())
    }

    @Test
    fun `세션 쿠키가 있는 점주 요청은 Redis를 읽지 못하면 HTML이 아니라 503 envelope다`() {
        val unknownSession = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

        send("GET", "/owner/v1/stores", cookie = "__Host-SESSION=$unknownSession").assertServiceUnavailableEnvelope()
    }

    @Test
    fun `로그인은 세션을 저장하지 못하면 503 envelope다`() {
        val csrf = send("GET", "/owner/v1/csrf").headers().allValues("Set-Cookie")
            .first { it.startsWith("__Host-XSRF-TOKEN=") }.substringBefore(";").substringAfter("=")
        val cookie = "__Host-XSRF-TOKEN=$csrf"
        val credentials = """{ "email": "${uniqueOwnerEmail()}", "password": "$OWNER_PASSWORD" }"""
        assertEquals(201, send("POST", "/owner/v1/signup", credentials, cookie, csrf).statusCode())

        send("POST", "/owner/v1/login", credentials, cookie, csrf).assertServiceUnavailableEnvelope()
    }

    @Test
    fun `같은 상태에서 단말 요청은 영향이 없다`() {
        val terminal = terminals.registerTerminal()

        val response = send("POST", "/v1/terminals/${terminal.terminalId.value}/sync", """{ "operations": [] }""", bearer = terminal.bearer)

        assertEquals(200, response.statusCode(), response.body())
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", PostgresIntegrationTest.postgres::getJdbcUrl)
            registry.add("spring.datasource.username", PostgresIntegrationTest.postgres::getUsername)
            registry.add("spring.datasource.password", PostgresIntegrationTest.postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", PostgresIntegrationTest.kafka::getBootstrapServers)
            registry.add("spring.data.redis.host") { "localhost" }
            registry.add("spring.data.redis.port") { unusedPort() }
        }

        private fun unusedPort(): Int = ServerSocket(0).use { it.localPort }
    }
}
