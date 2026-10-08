package com.resilientpos.platform

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataAccessException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Spring Session 필터(서블릿 필터)가 세션을 읽고 쓰다 Redis 오류를 던지면 MVC 밖이라 [ApiControllerAdvice]에 닿지 않고 Tomcat의 HTML 500이 나간다.
 * 이 필터를 그 필터 바깥에 두고, 체인에서 올라온 [DataAccessException]을 503 envelope로 바꾼다. 세션 읽기(요청 처리 중)와 저장(응답 커밋 때)을 모두 덮는다.
 * 응답이 이미 커밋됐으면 바꿀 수 없어 다시 던진다. 등록(경로·순서)은 [PlatformConfiguration]이 한다.
 */
class OwnerSessionStoreFilter(private val resolver: () -> HandlerExceptionResolver) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: DataAccessException) {
            if (response.isCommitted) throw e
            response.reset()
            resolver().resolveException(request, response, null, SessionStoreUnavailableException(e))
        }
    }
}
