package com.resilientpos.platform

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * 점주 경로(`/owner` 아래)만 맡는 Security 체인이다. 다른 경로는 어떤 체인과도 맞지 않아 Security 필터를 지나지 않는다.
 * 단말 인증(`/v1`)은 인터셉터가 그대로 맡는다.
 *
 * 인증은 Spring Session(Redis)의 세션 쿠키다. 가입·로그인·CSRF 토큰 발급만 누구나 부르고 나머지는 로그인한 점주만 부른다.
 * 로그인은 form login이 아니라 `OwnerController`가 자격을 확인해 세션에 `SecurityContext`를 저장한다.
 * 쿠키로 인증하므로 CSRF는 SPA 방식(`csrf.spa()`: 쿠키 `XSRF-TOKEN`, 헤더 `X-XSRF-TOKEN`)으로 가입·로그인·로그아웃 포함
 * 모든 상태 변경 요청에 요구한다. 로그아웃은 Security의 logout 필터가 세션을 지우고 `204`를 쓴다.
 * 요청 캐시는 끈다. 로그인 전 요청을 세션에 저장하면 인증 없는 요청이 Redis 세션을 만든다.
 *
 * 오류 응답은 직접 쓰지 않고 `handlerExceptionResolver`에 넘겨 [ApiControllerAdvice]가 envelope와 로그를 맡게 한다.
 * 401(세션 없음·만료)은 `WWW-Authenticate` 없는 [UnauthenticatedException]이고, CSRF 실패는 403 `ACCESS_DENIED`다.
 *
 * `@Bean` 메서드가 아니라 `SecurityFilterChain` 타입의 컴포넌트인 이유: `@WebMvcTest`는 이 타입의 컴포넌트만 슬라이스에 싣는다.
 * 슬라이스에 체인이 없으면 Boot 기본 체인이 `/v1`까지 막는다.
 */
@Component
class OwnerSecurityFilterChain(
    http: HttpSecurity,
    @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
) : SecurityFilterChain by chain(http, resolver) {

    private companion object {
        val log by logger()

        fun chain(http: HttpSecurity, resolver: HandlerExceptionResolver): SecurityFilterChain {
            // 세션 없음·만료를 나누지 않고 모두 같은 401이다. 원인 클래스만 DEBUG로 남긴다.
            val entryPoint = AuthenticationEntryPoint { request, response, cause ->
                log.debug { "owner unauthenticated uri=${request.requestURI} cause=${cause.javaClass.simpleName}" }
                resolver.resolveException(request, response, null, UnauthenticatedException())
            }
            val accessDenied = AccessDeniedHandler { request, response, e -> resolver.resolveException(request, response, null, e) }
            // Kotlin DSL에는 spa()가 없어 Java API로 건다. OwnerController가 로그인 뒤 토큰을 다시 만들 때 같은 쿠키 방식을 쓴다.
            http.csrf { it.spa() }
            http {
                securityMatcher("/owner/**")
                authorizeHttpRequests {
                    authorize(HttpMethod.POST, "/owner/v1/signup", permitAll)
                    authorize(HttpMethod.POST, "/owner/v1/login", permitAll)
                    authorize(HttpMethod.GET, "/owner/v1/csrf", permitAll)
                    authorize(anyRequest, authenticated)
                }
                exceptionHandling {
                    authenticationEntryPoint = entryPoint
                    accessDeniedHandler = accessDenied
                }
                requestCache { requestCache = NullRequestCache() }
                logout {
                    logoutUrl = "/owner/v1/logout"
                    logoutSuccessHandler = HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)
                }
            }
            return http.build()
        }
    }
}
