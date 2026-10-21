package com.resilientpos.platform

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import java.time.Duration
import java.time.Instant
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.web.client.RestTemplate

/**
 * 사내 IdP가 발급한 것처럼 서명한 플랫폼 운영자 토큰을 만든다. 서명 키는 JVM마다 새로 만드는 테스트 전용 RSA 키이고 어떤 환경의 비밀도 아니다.
 *
 * 앱은 Boot의 issuer decoder(첫 검증 때 discovery → JWK Set 조회)로 토큰을 검증한다. 토큰을 검증하는 컨텍스트는 [IdpStub]을 `@Import`해
 * 그 두 응답을 네트워크 없이 이 키로 대신 준다. 그래서 discovery·서명·issuer·audience·만료 검사가 운영과 같은 구성으로 돈다.
 */
object OperatorTokens {
    /** `config/application.properties`의 issuer-uri·audiences와 같아야 한다. */
    const val ISSUER = "https://operator-idp.test"
    const val AUDIENCE = "resilientpos-admin-test"
    const val SUBJECT = "operator-subject-1"
    private const val KEY_ID = "operator-test-key"

    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()

    /** 같은 kid의 다른 키. 이 키로 서명한 토큰은 키 조회가 아니라 서명 검사에서 거절된다. */
    val impostorKey: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()

    /** `Authorization` 헤더 값. 기본값은 통과하는 운영자 토큰이고, 인자 하나만 바꿔 실패 사례를 만든다. */
    fun bearer(
        subject: String = SUBJECT,
        roles: List<String> = listOf(AdminSecurityFilterChain.OPERATOR_AUTHORITY),
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(5)),
        key: RSAKey = signingKey,
    ): String {
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(subject)
            .audience(listOf(audience))
            .issuedAt(expiresAt.minus(Duration.ofMinutes(10)))
            .expiresAt(expiresAt)
            .claim("roles", roles)
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.keyID).build()
        val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(key)))
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    /** discovery·JWK Set 응답. [jwksAvailable]이 거짓을 돌려주면 JWK Set만 500이다(discovery 뒤의 IdP 장애). */
    fun idpResponses(jwksAvailable: () -> Boolean = { true }) = ClientHttpRequestFactory { uri, method ->
        val (status, body) = when (uri.toString()) {
            "$ISSUER/.well-known/openid-configuration" -> HttpStatus.OK to """{"issuer":"$ISSUER","jwks_uri":"$ISSUER/jwks"}"""
            "$ISSUER/jwks" -> if (jwksAvailable()) HttpStatus.OK to JWKSet(signingKey.toPublicJWK()).toString() else HttpStatus.INTERNAL_SERVER_ERROR to "{}"
            else -> error("IdP stub has no response for $method $uri")
        }
        MockClientHttpRequest(method, uri).apply {
            setResponse(MockClientHttpResponse(body.toByteArray(), status).apply { headers.contentType = MediaType.APPLICATION_JSON })
        }
    }

    /** Boot decoder의 HTTP 호출(discovery·JWK Set)을 [idpResponses]로 돌린다. 이 설정이 없는 컨텍스트는 `.test` 주소라 IdP에 닿지 못한다. */
    @TestConfiguration(proxyBeanMethods = false)
    class IdpStub {
        @Bean
        fun operatorIdpStub() = JwkSetUriJwtDecoderBuilderCustomizer { builder -> builder.restOperations(RestTemplate(idpResponses())) }
    }
}
