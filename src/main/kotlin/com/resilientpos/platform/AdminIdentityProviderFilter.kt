package com.resilientpos.platform

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * 운영자 체인([AdminSecurityFilterChain])이 IdP에 닿지 못하면 예외가 Security 필터 밖으로 나가 Tomcat의 HTML 500이 된다. 경로는 둘이다.
 * - 첫 검증의 decoder 초기화(issuer discovery와 JWK Set 첫 조회) 실패: `SupplierJwtDecoder`가 [JwtDecoderInitializationException]으로 감싼다.
 *   `AuthenticationException`이 아니라 체인의 entry point를 거치지 않는다.
 * - 초기화 뒤 JWK Set 재조회 실패: `JwtAuthenticationProvider`가 [AuthenticationServiceException]으로 바꾸고, resource server의 실패 처리기
 *   (`AuthenticationEntryPointFailureHandler`, 기본 rethrow)가 다시 던진다.
 *
 * 이 필터를 Spring Security 필터 바깥에 두고 두 예외를 503 envelope로 바꾼다. 응답이 이미 커밋됐으면 바꿀 수 없어 다시 던진다.
 * 같은 모양의 [OwnerSessionStoreFilter]와 잡는 예외·경로가 달라 합치지 않는다. 등록(경로·순서)은 [PlatformConfiguration]이 한다.
 */
class AdminIdentityProviderFilter(private val resolver: () -> HandlerExceptionResolver) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: JwtDecoderInitializationException) {
            unavailable(request, response, e)
        } catch (e: AuthenticationServiceException) {
            unavailable(request, response, e)
        }
    }

    private fun unavailable(request: HttpServletRequest, response: HttpServletResponse, e: RuntimeException) {
        if (response.isCommitted) throw e
        response.reset()
        resolver().resolveException(request, response, null, DependencyUnavailableException("operator IdP", e))
    }
}
