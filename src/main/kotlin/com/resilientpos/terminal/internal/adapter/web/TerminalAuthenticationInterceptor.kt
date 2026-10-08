package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.UnauthenticatedException
import com.resilientpos.terminal.TerminalManagement
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * v1 경로 요청의 단말 토큰을 검증하고 결과를 요청 속성에 둔다. 토큰과 헤더 값은 로그에도 예외에도 남기지 않는다.
 * 예외는 `ApiControllerAdvice`가 401 envelope로 바꾼다.
 */
@Component
class TerminalAuthenticationInterceptor(private val terminals: TerminalManagement) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // 스킴은 대소문자를 구분하지 않는다(RFC 7235 2.1). 토큰 형식이 아니면 해시·DB 조회 없이 거른다.
        val token = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)?.takeIf { TOKEN_FORMAT.matches(it) }
            ?: throw UnauthenticatedException(UnauthenticatedException.BEARER)
        request.setAttribute(ATTRIBUTE, terminals.authenticate(token) ?: throw UnauthenticatedException(UnauthenticatedException.BEARER))
        return true
    }

    companion object {
        const val ATTRIBUTE = "com.resilientpos.terminal.AuthenticatedTerminal"
        private const val BEARER_PREFIX = "Bearer "

        /** `TerminalCredential.issue`의 토큰 형식: 32바이트의 base64url(패딩 없음) 43자. */
        private val TOKEN_FORMAT = Regex("[A-Za-z0-9_-]{43}")
    }
}
