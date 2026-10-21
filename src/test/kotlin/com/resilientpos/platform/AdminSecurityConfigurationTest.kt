package com.resilientpos.platform

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.servlet.HandlerExceptionResolver

private const val ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri"
private const val AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences"

/**
 * IdP 설정이 빠지면 앱이 뜨지 않는다. 운영 설정은 환경 변수가 없을 때 빈 문자열로 풀리므로(`application.yaml`) 같은 값을 넣는다.
 * 전체 애플리케이션 대신 운영자 체인과 그것이 기대는 Boot 자동 구성만 띄운다. 첫 테스트가 이 구성으로 체인이 만들어짐을 보여 실패의 원인을 설정 하나로 좁힌다.
 */
class AdminSecurityConfigurationTest {

    private val runner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SecurityAutoConfiguration::class.java,
                ServletWebSecurityAutoConfiguration::class.java,
                OAuth2ResourceServerAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(AdminSecurityFilterChain::class.java)
        .withBean("handlerExceptionResolver", HandlerExceptionResolver::class.java, { HandlerExceptionResolver { _, _, _, _ -> null } })

    private fun rootCauseMessage(failure: Throwable): String = generateSequence(failure) { it.cause }.last().toString()

    @Test
    fun `issuer와 audience가 있으면 운영자 체인이 만들어진다`() {
        runner.withPropertyValues("$ISSUER=https://operator-idp.test", "$AUDIENCES=resilientpos-admin").run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(AdminSecurityFilterChain::class.java))
        }
    }

    @Test
    fun `issuer가 비면 체인이 거부해 컨텍스트가 뜨지 않는다`() {
        runner.withPropertyValues("$ISSUER=", "$AUDIENCES=resilientpos-admin").run { context ->
            val failure = assertNotNull(context.startupFailure, "issuer 없이 컨텍스트가 떴다")
            assertContains(rootCauseMessage(failure), ISSUER)
        }
    }

    @Test
    fun `issuer 없이 jwk-set-uri만 있으면 issuer 검사가 빠지므로 컨텍스트가 뜨지 않는다`() {
        runner.withPropertyValues(
            "$ISSUER=",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://operator-idp.test/jwks",
            "$AUDIENCES=resilientpos-admin",
        ).run { context ->
            val failure = assertNotNull(context.startupFailure, "issuer 없이 컨텍스트가 떴다")
            assertContains(rootCauseMessage(failure), ISSUER)
        }
    }

    @Test
    fun `audience가 비면 체인이 거부해 컨텍스트가 뜨지 않는다`() {
        runner.withPropertyValues("$ISSUER=https://operator-idp.test", "$AUDIENCES=").run { context ->
            val failure = assertNotNull(context.startupFailure, "audience 없이 컨텍스트가 떴다")
            assertContains(rootCauseMessage(failure), AUDIENCES)
        }
    }
}
