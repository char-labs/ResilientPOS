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
        val token = request.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw UnauthenticatedException()
        request.setAttribute(ATTRIBUTE, terminals.authenticate(token) ?: throw UnauthenticatedException())
        return true
    }

    companion object {
        const val ATTRIBUTE = "com.resilientpos.terminal.AuthenticatedTerminal"
        private const val BEARER_PREFIX = "Bearer "
    }
}
