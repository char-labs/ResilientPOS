package com.resilientpos.platform

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException

private const val ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri"
private const val AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences"

/**
 * IdP HTTP 상한([AdminIdentityProviderTimeouts])이 Boot의 issuer decoder에 실제로 붙는지 본다. 운영자 체인 없이 decoder 자동 구성만 띄운다.
 */
class AdminIdentityProviderTimeoutsTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration::class.java))
        .withUserConfiguration(AdminIdentityProviderTimeouts::class.java)

    private val token = OperatorTokens.bearer().removePrefix("Bearer ")

    @Test
    fun `연결은 받고 응답하지 않는 IdP면 issuer discovery가 기본 30초가 아니라 read 상한 안에 실패한다`() {
        // accept하지 않아도 backlog가 연결을 받아 두므로 connect는 성공하고 응답만 오지 않는다.
        ServerSocket(0).use { silentIdp ->
            runner.withPropertyValues("$ISSUER=http://127.0.0.1:${silentIdp.localPort}").run { context ->
                val decoder = context.getBean(JwtDecoder::class.java)
                val startedAt = System.nanoTime()

                val failure = assertFailsWith<JwtDecoderInitializationException> { decoder.decode(token) }

                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is SocketTimeoutException }, failure.stackTraceToString())
                assertTrue(elapsed < AdminIdentityProviderTimeouts.READ_TIMEOUT.multipliedBy(2), "elapsed=$elapsed")
            }
        }
    }

    @Test
    fun `운영 상한이 먼저 적용되므로 뒤에 붙는 customizer가 decoder의 HTTP 호출을 바꿀 수 있다`() {
        // withBean 등록이 설정 클래스보다 먼저라, 순서를 정하지 않으면 운영 상한이 나중에 적용돼 테스트의 IdP 응답을 덮는다.
        runner.withBean(JwkSetUriJwtDecoderBuilderCustomizer::class.java, { OperatorTokens.IdpStub().operatorIdpStub() })
            .withPropertyValues("$ISSUER=${OperatorTokens.ISSUER}", "$AUDIENCES=${OperatorTokens.AUDIENCE}")
            .run { context ->
                val jwt = context.getBean(JwtDecoder::class.java).decode(token)

                assertEquals(OperatorTokens.SUBJECT, jwt.subject)
            }
    }
}
