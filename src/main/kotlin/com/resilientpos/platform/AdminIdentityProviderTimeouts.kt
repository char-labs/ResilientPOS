package com.resilientpos.platform

import java.time.Duration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * 운영자 IdP HTTP 호출(issuer discovery·JWK Set)의 상한이다. Spring Security 기본값(시스템 속성이 없으면 connect·read 각 30초)이면
 * IdP가 응답을 멈췄을 때 decoder 초기화 잠금 뒤에 다른 `/admin` 요청이 줄을 서서 요청 스레드를 수십 초씩 쥔다.
 *
 * Boot가 issuer로 만드는 decoder의 builder에서 HTTP 클라이언트만 바꾸므로 Boot의 issuer·audience 검사기는 그대로다.
 * Boot는 customizer를 순서대로 적용하므로 가장 먼저 적용해, 뒤에 붙는 customizer(테스트의 IdP 응답 대체)가 클라이언트를 바꿀 수 있게 둔다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdminIdentityProviderTimeouts : JwkSetUriJwtDecoderBuilderCustomizer {
    override fun customize(builder: NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder) {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(CONNECT_TIMEOUT)
            setReadTimeout(READ_TIMEOUT)
        }
        builder.restOperations(RestTemplate(requestFactory))
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
