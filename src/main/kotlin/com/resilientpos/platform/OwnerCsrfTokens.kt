package com.resilientpos.platform

import org.springframework.security.web.csrf.CookieCsrfTokenRepository

/**
 * 점주 경로의 CSRF 토큰 쿠키 저장소. 체인(`OwnerSecurityFilterChain`)과 로그인(`OwnerController`)이 같은 설정을 쓰도록 한 인스턴스를 공유한다.
 * 쿠키 `__Host-XSRF-TOKEN`은 `__Host-` 규칙대로 Secure·`Path=/`·Domain 없음이다. JavaScript가 읽어 `X-XSRF-TOKEN` 헤더로 보내므로 HttpOnly가 아니다.
 */
object OwnerCsrfTokens {
    val repository: CookieCsrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
        setCookieName("__Host-XSRF-TOKEN")
        setCookiePath("/")
        setCookieCustomizer { it.secure(true) }
    }
}
