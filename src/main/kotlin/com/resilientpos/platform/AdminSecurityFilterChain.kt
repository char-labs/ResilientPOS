package com.resilientpos.platform

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * 플랫폼 운영자 경로(`/admin` 아래)만 맡는 Security 체인이다. 점주 체인(`/owner`)과 단말 인터셉터(`/v1`)는 이 토큰을 모른다.
 *
 * 인증은 사내 IdP가 발급한 Bearer JWT다. 앱은 resource server로 검증만 하고, decoder는 Boot가 `issuer-uri`로 만든다
 * (서명·만료·issuer, `audiences`가 있으면 aud). 모든 요청에 [OPERATOR_AUTHORITY] 권한이 필요하고, 권한을 읽을 claim은
 * Boot 속성(`authorities-claim-name`, `authority-prefix`)으로 배포가 정한다. 쿠키를 읽지도 쓰지도 않으므로 세션은 stateless이고 CSRF를 끈다.
 * IdP HTTP 호출(discovery·JWK Set)의 상한은 [AdminIdentityProviderTimeouts]가 정한다.
 *
 * issuer와 audience는 둘 다 필수이고 비어 있으면 이 체인이 생성을 거부해 앱이 뜨지 않는다. Boot는 둘 다 없어도 검사 없이 넘어가는 경로가 있다
 * (issuer 없이 jwk-set-uri만 주면 iss를, audiences가 비면 aud를 검사하지 않는다). 체인이 없으면 `/admin`이 어떤 체인에도 맞지 않아 인증 없이 열리고,
 * aud를 검사하지 않으면 같은 IdP가 다른 앱에 발급한 토큰도 통과한다.
 *
 * 오류 응답은 `handlerExceptionResolver`에 넘겨 [ApiControllerAdvice]가 envelope와 로그를 맡게 한다. 토큰이 없거나 틀리면 원인과 관계없이
 * `WWW-Authenticate: Bearer`의 401, 권한이 없으면 403 `ACCESS_DENIED`다. 토큰 값은 로그와 예외 메시지에 싣지 않는다.
 *
 * `@Bean` 메서드가 아닌 `SecurityFilterChain` 컴포넌트인 이유는 [OwnerSecurityFilterChain]과 같다(`@WebMvcTest` 슬라이스에 실린다).
 */
@Component
class AdminSecurityFilterChain(
    http: HttpSecurity,
    @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
    properties: OAuth2ResourceServerProperties,
) : SecurityFilterChain by chain(http, resolver, properties) {

    companion object {
        const val OPERATOR_AUTHORITY = "resilientpos-operator"

        private val log by logger()
        private val CONTROL_CHARACTERS = Regex("\\p{Cntrl}")

        private fun chain(http: HttpSecurity, resolver: HandlerExceptionResolver, properties: OAuth2ResourceServerProperties): SecurityFilterChain {
            // jwk-set-uri나 public-key-location만 주면 Boot는 issuer 없이 decoder를 만들어 iss를 검사하지 않는다.
            check(!properties.jwt.issuerUri.isNullOrBlank()) {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri(OPERATOR_ISSUER_URI)가 비어 있다. 비우면 다른 IdP가 발급한 토큰의 issuer를 검사하지 않는다"
            }
            check(properties.jwt.audiences.any { it.isNotBlank() }) {
                "spring.security.oauth2.resourceserver.jwt.audiences(OPERATOR_AUDIENCE)가 비어 있다. 비우면 다른 앱용 토큰도 /admin을 통과한다"
            }
            // 원인(없음·서명·만료·issuer·audience)을 나누지 않고 같은 401이다. 빈 JWK Set·키 회전 같은 IdP 쪽 이상을 가르도록 원인은 INFO로 남긴다.
            // 원인 메시지는 Spring Security·Nimbus의 검증 문구이고 토큰 값을 담지 않는다. 형식이 깨진 토큰의 파싱 문구는 요청에서 온 문자를 담을 수 있어
            // 제어 문자를 지워 평문 로그의 줄 위조를 막는다.
            val entryPoint = AuthenticationEntryPoint { request, response, cause ->
                val message = cause.message?.replace(CONTROL_CHARACTERS, "?")
                log.info { "operator unauthenticated uri=${request.requestURI} cause=${cause.javaClass.simpleName}: $message" }
                resolver.resolveException(request, response, null, UnauthenticatedException(UnauthenticatedException.BEARER))
            }
            val accessDenied = AccessDeniedHandler { request, response, e -> resolver.resolveException(request, response, null, e) }
            http {
                securityMatcher("/admin/**")
                authorizeHttpRequests { authorize(anyRequest, hasAuthority(OPERATOR_AUTHORITY)) }
                // 토큰이 틀린 경우는 resource server 필터가, 토큰이 없는 경우는 예외 변환 필터가 entry point를 부른다.
                oauth2ResourceServer {
                    authenticationEntryPoint = entryPoint
                    jwt { }
                }
                exceptionHandling {
                    authenticationEntryPoint = entryPoint
                    accessDeniedHandler = accessDenied
                }
                sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
                csrf { disable() }
            }
            return http.build()
        }
    }
}
