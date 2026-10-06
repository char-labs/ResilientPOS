package com.resilientpos.platform

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * 운영자 경로(`/admin` 아래)만 맡는 Security 체인이다. 다른 경로는 어떤 체인과도 맞지 않아 Security 필터를 지나지 않는다.
 * 단말 인증(`/v1`)은 인터셉터가 그대로 맡는다. JWT 검증(서명·exp·iss·aud)과 역할 매핑은 Boot 자동 구성이
 * `spring.security.oauth2.resourceserver.jwt.*` 설정으로 하고 `JwtDecoder`를 직접 만들지 않는다.
 * 오류 응답은 직접 쓰지 않고 `handlerExceptionResolver`에 넘겨 [ApiControllerAdvice]가 envelope와 로그를 맡게 한다.
 *
 * `@Bean` 메서드가 아니라 `SecurityFilterChain` 타입의 컴포넌트인 이유: `@WebMvcTest`는 이 타입의 컴포넌트만 슬라이스에 싣는다.
 * 슬라이스에 체인이 없으면 Boot 기본 체인이 `/v1`까지 막는다. 슬라이스에는 resource server 자동 구성이 없어
 * `JwtDecoder` 빈이 없으므로 디코더는 요청 때 찾는다. `/admin`을 부르지 않는 슬라이스에서는 찾지 않는다.
 */
@Component
class OperatorSecurityFilterChain(
    http: HttpSecurity,
    @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
    decoder: ObjectProvider<JwtDecoder>,
) : SecurityFilterChain by chain(http, resolver, decoder) {

    private companion object {
        fun chain(http: HttpSecurity, resolver: HandlerExceptionResolver, decoder: ObjectProvider<JwtDecoder>): SecurityFilterChain {
            // 원인(서명·만료·iss·aud·토큰 없음)을 나누지 않고 모두 같은 401이다.
            val entryPoint = AuthenticationEntryPoint { request, response, _ ->
                resolver.resolveException(request, response, null, UnauthenticatedException())
            }
            val accessDenied = AccessDeniedHandler { request, response, e -> resolver.resolveException(request, response, null, e) }
            http {
                securityMatcher("/admin/**")
                authorizeHttpRequests { authorize(anyRequest, hasRole("OPERATOR")) }
                sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
                // 쿠키·세션 없이 Bearer 헤더만 받는다.
                csrf { disable() }
                oauth2ResourceServer {
                    authenticationEntryPoint = entryPoint
                    accessDeniedHandler = accessDenied
                    jwt { jwtDecoder = JwtDecoder { token -> decoder.getObject().decode(token) } }
                }
            }
            return http.build()
        }
    }
}
