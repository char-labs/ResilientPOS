package com.resilientpos.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 오류 로그 한 줄만으로 어떤 요청이었는지 특정할 수 있어야 한다.
 * 형식을 눈으로 보고 넘어가면 다음 변경이 조용히 지우므로 실제 로그를 가로채서 확인한다.
 */
@WebMvcTest(ApiAdviceTestController::class)
class ErrorLogRequestContextTest(@Autowired private val mockMvc: MockMvc) {

    private val logger = LoggerFactory.getLogger(ApiControllerAdvice::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeTest
    fun attach() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.TRACE
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `도메인 규칙 위반 로그에 메서드와 URI가 남는다`() {
        mockMvc.get("/test-advice/domain")

        assertRequestContext(method = "GET", uri = "/test-advice/domain")
    }

    @Test
    fun `경로 변수를 쓰는 요청은 URI만으로 대상이 드러난다`() {
        mockMvc.get("/test-advice/domain/NOT_FOUND")

        assertRequestContext(method = "GET", uri = "/test-advice/domain/NOT_FOUND")
    }

    @Test
    fun `예상치 못한 예외 로그에도 메서드와 URI가 남고 원인이 보존된다`() {
        mockMvc.get("/test-advice/boom")

        val event = assertRequestContext(method = "GET", uri = "/test-advice/boom")
        assertTrue(event.throwableProxy != null, "예외 원인이 로그에 남아야 한다")
    }

    @Test
    fun `Spring MVC 표준 예외 로그에도 남는다`() {
        mockMvc.post("/test-advice/object")

        assertRequestContext(method = "POST", uri = "/test-advice/object")
    }

    @Test
    fun `쿼리 문자열은 로그에 남지 않는다`() {
        mockMvc.get("/test-advice/number?n=abc")

        val event = assertRequestContext(method = "GET", uri = "/test-advice/number")
        assertTrue("n=abc" !in event.formattedMessage, "쿼리 문자열이 로그에 남았다: ${event.formattedMessage}")
    }

    private fun assertRequestContext(method: String, uri: String): ILoggingEvent {
        val event = appender.list.singleOrNull() ?: error("오류 로그가 한 줄이 아니다: ${appender.list.map { it.formattedMessage }}")
        val message = event.formattedMessage

        assertTrue("method=$method" in message, "메서드가 없다: $message")
        assertTrue("uri=$uri" in message, "URI가 없다: $message")
        assertTrue("errorCode=" in message, "errorCode가 없다: $message")
        return event
    }

    @Test
    fun `성공 응답은 오류 로그를 남기지 않는다`() {
        mockMvc.get("/test-advice/object")

        assertEquals(0, appender.list.size)
    }
}
