package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.UnauthenticatedException
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.internal.adapter.redis.RedisTerminalRateLimiter
import com.resilientpos.terminal.internal.adapter.web.TerminalInFlightLimiter.Kind
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * v1 경로 요청의 단말 토큰을 검증하고, 인증 뒤 동시 상한과 빈도 상한을 이 순서로 확인한 다음 결과를 요청 속성에 둔다.
 * 토큰과 헤더 값은 로그에도 예외에도 남기지 않는다. 예외는 `ApiControllerAdvice`가 401(인증 실패)·429(상한 초과) envelope로 바꾼다.
 *
 * 동시 상한([TerminalInFlightLimiter])이 빈도 상한보다 먼저다. 동시 상한이 거절한 요청은 token을 쓰지 않아야, 단말이 같은 요청을 다시 보낼 때 token이 줄지 않는다.
 * 인증에 실패한 요청은 자리를 잡지 않는다.
 *
 * 잡은 자리는 `afterCompletion`에서 푼다. handler가 예외로 끝나도 불린다. `preHandle`이 자리를 잡은 뒤 던지면(빈도 상한 거절 등)
 * `afterCompletion`은 불리지 않으므로(`HandlerInterceptor`는 `preHandle`이 true를 돌려준 뒤에만 부른다) 던지기 전에 그 자리에서 푼다.
 *
 * 비동기 handler(`Callable`·`DeferredResult`·suspend)는 ASYNC dispatch에서 이 메서드를 다시 부른다. 자리는 처음 dispatch의 것을 이어 쓰고 마지막 `afterCompletion`이 한 번 푼다.
 * 인증 조회와 토큰 차감은 두 번 일어난다. 지금 v1에는 그런 handler가 없다.
 */
@Component
class TerminalAuthenticationInterceptor(
    private val terminals: TerminalManagement,
    private val rateLimiter: RedisTerminalRateLimiter,
    private val inFlight: TerminalInFlightLimiter,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        var admitted = false
        try {
            // 스킴은 대소문자를 구분하지 않는다(RFC 7235 2.1). 토큰 형식이 아니면 해시·DB 조회 없이 거른다.
            val token = request.getHeader(HttpHeaders.AUTHORIZATION)
                ?.takeIf { it.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true) }
                ?.substring(BEARER_PREFIX.length)?.takeIf { TOKEN_FORMAT.matches(it) }
                ?: throw UnauthenticatedException(UnauthenticatedException.BEARER)
            val terminal = terminals.authenticate(token) ?: throw UnauthenticatedException(UnauthenticatedException.BEARER)
            if (request.getAttribute(HELD_SLOT) == null) {
                val kind = if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) == SYNC_PATTERN) Kind.SYNC else Kind.REQUEST
                inFlight.acquire(terminal, kind)
                request.setAttribute(HELD_SLOT, HeldSlot(terminal, kind))
            }
            rateLimiter.acquire(terminal)
            request.setAttribute(ATTRIBUTE, terminal)
            admitted = true
            return true
        } finally {
            if (!admitted) releaseSlot(request)
        }
    }

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        releaseSlot(request)
    }

    private fun releaseSlot(request: HttpServletRequest) {
        val held = request.getAttribute(HELD_SLOT) as? HeldSlot ?: return
        request.removeAttribute(HELD_SLOT)
        inFlight.release(held.terminal, held.kind)
    }

    companion object {
        const val ATTRIBUTE = "com.resilientpos.terminal.AuthenticatedTerminal"
        private const val HELD_SLOT = "com.resilientpos.terminal.InFlightSlot"

        /** sync 모듈 `SyncController`의 매핑과 같아야 한다. 실제 sync 요청으로 묶음을 고정하는 통합 테스트가 따로 있다. */
        private const val SYNC_PATTERN = "/v1/terminals/{terminalId}/sync"
        private const val BEARER_PREFIX = "Bearer "

        /** `TerminalCredential.issue`의 토큰 형식: 32바이트의 base64url(패딩 없음) 43자. */
        private val TOKEN_FORMAT = Regex("[A-Za-z0-9_-]{43}")
    }
}

/** 요청이 쥔 자리. 중첩하지 않는 이유는 `acquire`는 인증 인터셉터만 부른다 구조 테스트가 중첩 클래스까지 인터셉터로 보고 `acquire` 호출을 요구하기 때문이다. */
private data class HeldSlot(val terminal: AuthenticatedTerminal, val kind: Kind)
