package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.session.data.redis.RedisSessionRepository

/**
 * 세션 읽기는 성공하고 응답을 커밋할 때 세션 저장만 실패하는 경로(결정 12). Redis는 살아 있고,
 * 테스트 설정이 Spring Session의 세션 저장소 빈(`sessionRepository`, [RedisSessionRepository])을 감싸 `save`만 스위치로 실패시킨다.
 * 컨텍스트가 하나 더 뜬다(받아들임). 스위치는 이 컨텍스트에만 있어 다른 통합 테스트에 영향이 없다.
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(OwnerSessionSaveFailureIntegrationTest.FailingSaveConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilientpos.kafka.partitions=1",
        "resilientpos.kafka.retry.initial-interval=PT0.02S",
        "resilientpos.event-publication.resubmit-interval=P1D",
        "resilientpos.payment.unknown-resolve-interval=P1D",
    ],
)
class OwnerSessionSaveFailureIntegrationTest(
    @LocalServerPort private val port: Int,
    @Autowired private val saveFails: AtomicBoolean,
) : PostgresIntegrationTest() {

    private val client = HttpClient.newHttpClient()

    @TestConfiguration
    class FailingSaveConfiguration {
        @Bean
        fun saveFails() = AtomicBoolean(false)

        @Bean
        fun failingSaveSessionRepository(saveFails: AtomicBoolean) = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is RedisSessionRepository) FailingSave(bean, saveFails) else bean
        }
    }

    // RedisSession이 package-private이라 세션 타입을 Session으로 둔다. 위임 대상이 자기 세션만 받으므로 실행 중에는 같은 객체다.
    @Suppress("UNCHECKED_CAST")
    class FailingSave(delegate: RedisSessionRepository, private val fails: AtomicBoolean) :
        SessionRepository<Session> by (delegate as SessionRepository<Session>) {
        private val target = delegate as SessionRepository<Session>

        override fun save(session: Session) {
            if (fails.get()) throw RedisConnectionFailureException("save failed (test)")
            target.save(session)
        }
    }

    @AfterTest
    fun restore() = saveFails.set(false)

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

    private fun HttpResponse<*>.cookieValue(name: String): String =
        assertNotNull(headers().allValues("Set-Cookie").firstOrNull { it.startsWith("$name=") }, headers().allValues("Set-Cookie").toString())
            .substringBefore(";").substringAfter("=")

    /** 로그인해 세션 쿠키 헤더 값과 CSRF 토큰을 돌려준다. */
    private fun logIn(): Pair<String, String> {
        val csrf = send("GET", "/owner/v1/csrf").cookieValue("__Host-XSRF-TOKEN")
        val credentials = """{ "email": "${uniqueOwnerEmail()}", "password": "$OWNER_PASSWORD" }"""
        assertEquals(201, send("POST", "/owner/v1/signup", credentials, "__Host-XSRF-TOKEN=$csrf", csrf).statusCode())
        val login = send("POST", "/owner/v1/login", credentials, "__Host-XSRF-TOKEN=$csrf", csrf)
        assertEquals(200, login.statusCode(), login.body())
        val newCsrf = login.cookieValue("__Host-XSRF-TOKEN")
        return "__Host-SESSION=${login.cookieValue("__Host-SESSION")}; __Host-XSRF-TOKEN=$newCsrf" to newCsrf
    }

    private fun HttpResponse<String>.assertServiceUnavailableEnvelope() {
        assertEquals(503, statusCode(), body())
        assertTrue(headers().firstValue("Content-Type").orElse("").startsWith("application/json"), headers().toString())
        assertTrue(body().contains(""""errorCode":"SERVICE_UNAVAILABLE""""), body())
        assertTrue(body().contains(""""result":"FAIL""""), body())
    }

    private fun CapturedOutput.assertSingleServiceUnavailableError(mark: Int) {
        val out = all.substring(mark)
        assertEquals(1, Regex("errorCode=SERVICE_UNAVAILABLE").findAll(out).count(), out)
        assertFalse(out.contains("errorCode=INTERNAL_ERROR"), out)
        assertFalse(out.contains("Failure in @ExceptionHandler"), out)
    }

    @Test
    fun `세션 읽기가 성공하고 저장만 실패하는 GET은 503 envelope와 ERROR 한 줄이다`(output: CapturedOutput) {
        val (cookie, _) = logIn()
        saveFails.set(true)
        val mark = output.all.length

        send("GET", "/owner/v1/stores", cookie = cookie).assertServiceUnavailableEnvelope()

        output.assertSingleServiceUnavailableError(mark)
    }

    @Test
    fun `세션 읽기가 성공하고 저장만 실패하는 POST도 503 envelope와 ERROR 한 줄이고 매장은 커밋된다`(output: CapturedOutput) {
        val (cookie, csrf) = logIn()
        saveFails.set(true)
        val mark = output.all.length

        send("POST", "/owner/v1/stores", """{ "name": "저장 실패 매장" }""", cookie, csrf).assertServiceUnavailableEnvelope()

        output.assertSingleServiceUnavailableError(mark)
        saveFails.set(false)
        assertTrue(send("GET", "/owner/v1/stores", cookie = cookie).body().contains("저장 실패 매장"), "503이어도 매장은 커밋됐다")
    }
}
