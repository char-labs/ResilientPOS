package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort

/**
 * 점주 세션·CSRF 쿠키의 속성을 실제 HTTP 응답의 `Set-Cookie`로 고정한다. MOCK 환경은 서블릿 컨텍스트 경로로 쿠키를 만들어
 * MockMvc에서는 `Secure`·`SameSite`를 볼 수 없다. `__Host-` 접두어는 브라우저가 Secure·`Path=/`·Domain 없음을 요구한다.
 * 컨텍스트가 하나 더 뜬다(받아들임). 부모의 `@SpringBootTest` 속성이 이 어노테이션에 가려지므로 같은 값을 다시 준다.
 */
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilientpos.kafka.partitions=1",
        "resilientpos.kafka.retry.initial-interval=PT0.02S",
        "resilientpos.event-publication.resubmit-interval=P1D",
        "resilientpos.payment.unknown-resolve-interval=P1D",
    ],
)
class OwnerCookieIntegrationTest(@LocalServerPort private val port: Int) : PostgresIntegrationTest() {

    private val client = HttpClient.newHttpClient()

    private fun send(method: String, path: String, body: String? = null, cookie: String? = null, xsrf: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .apply {
                if (body != null) header("Content-Type", "application/json")
                cookie?.let { header("Cookie", it) }
                xsrf?.let { header("X-XSRF-TOKEN", it) }
            }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<*>.setCookie(name: String): String =
        assertNotNull(headers().allValues("Set-Cookie").firstOrNull { it.startsWith("$name=") }, "Set-Cookie $name 없음: ${headers().allValues("Set-Cookie")}")

    private fun String.value(): String = substringBefore(";").substringAfter("=")

    @Test
    fun `CSRF 발급과 로그인 응답의 쿠키는 __Host- 접두어와 Secure Path 속성을 가지고 평문 비밀번호는 출력에 없다`(output: CapturedOutput) {
        val email = uniqueOwnerEmail()
        val credentials = """{ "email": "$email", "password": "$OWNER_PASSWORD" }"""

        val csrfCookie = send("GET", "/owner/v1/csrf").setCookie("__Host-XSRF-TOKEN")
        assertTrue(csrfCookie.endsWith("; Path=/; Secure"), csrfCookie)
        assertFalse(csrfCookie.contains("Domain"), csrfCookie)
        assertFalse(csrfCookie.contains("HttpOnly"), "JavaScript가 읽어 헤더로 보낸다: $csrfCookie")
        val csrf = csrfCookie.value()
        val csrfCookieHeader = "__Host-XSRF-TOKEN=$csrf"

        assertEquals(201, send("POST", "/owner/v1/signup", credentials, csrfCookieHeader, csrf).statusCode())
        val login = send("POST", "/owner/v1/login", credentials, csrfCookieHeader, csrf)
        assertEquals(200, login.statusCode())

        val session = login.setCookie("__Host-SESSION")
        assertTrue(session.endsWith("; Path=/; Secure; HttpOnly; SameSite=Lax"), session)
        assertFalse(session.contains("Domain"), session)
        val newCsrf = login.setCookie("__Host-XSRF-TOKEN")
        assertTrue(newCsrf.endsWith("; Path=/; Secure"), newCsrf)
        assertTrue(login.headers().allValues("Set-Cookie").none { it.startsWith("SESSION=") || it.startsWith("XSRF-TOKEN=") })

        val me = send("GET", "/owner/v1/stores", cookie = "__Host-SESSION=${session.value()}")
        assertEquals(200, me.statusCode())
        assertFalse(output.all.contains(OWNER_PASSWORD), "평문 비밀번호가 출력에 남았다")
    }
}
