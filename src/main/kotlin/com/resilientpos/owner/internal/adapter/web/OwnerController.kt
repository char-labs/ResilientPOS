package com.resilientpos.owner.internal.adapter.web

import com.resilientpos.owner.OwnerManagement
import com.resilientpos.platform.logger
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 점주 가입·로그인·CSRF 토큰 endpoint. 로그아웃은 `OwnerSecurityFilterChain`의 logout 필터가 맡는다.
 * 요청을 Command로 바꿔 Facade를 부르고, 로그인은 확인된 점주 ID로 세션 인증을 만든다.
 * 감사 로그는 Facade가 돌아온 뒤 한 줄이며 이메일·비밀번호·세션 ID는 남기지 않는다.
 */
@RestController
@RequestMapping("/owner/v1")
class OwnerController(private val management: OwnerManagement) {

    /** 로그인시키지 않는다. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signUp(@RequestBody request: SignUpRequest): OwnerResponse {
        val result = management.signUp(request.toCommand())
        log.info { "audit action=OWNER_SIGNED_UP ownerId=${result.ownerId.value}" }
        return OwnerResponse.from(result)
    }

    /**
     * 자격을 확인한 뒤 principal이 점주 UUID인 인증을 세션에 저장한다. form login을 쓰지 않는다(JSON·envelope 응답).
     * 세션 고정을 막으려고 로그인 전 세션이 있으면 ID를 바꾸고, 로그인 전 CSRF 토큰은 새 값으로 교체한다.
     * CSRF 쿠키 저장소는 체인의 `csrf.spa()`와 같은 설정이다.
     */
    @PostMapping("/login")
    fun logIn(@RequestBody request: LogInRequest, httpRequest: HttpServletRequest, httpResponse: HttpServletResponse): OwnerResponse {
        val result = management.logIn(request.toCommand())
        val authentication = UsernamePasswordAuthenticationToken.authenticated(result.ownerId.value, null, emptyList())
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        sessionFixation.onAuthentication(authentication, httpRequest, httpResponse)
        csrfTokens.saveToken(csrfTokens.generateToken(httpRequest), httpRequest, httpResponse)
        securityContexts.saveContext(context, httpRequest, httpResponse)
        log.info { "audit action=OWNER_LOGGED_IN ownerId=${result.ownerId.value}" }
        return OwnerResponse.from(result)
    }

    /** 토큰을 꺼내는 순간 `XSRF-TOKEN` 쿠키가 심긴다. 토큰을 읽지 않으면 쿠키가 만들어지지 않는다(지연 토큰). */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun csrf(csrfToken: CsrfToken) {
        csrfToken.token
    }

    private companion object {
        val log by logger()
        val sessionFixation = ChangeSessionIdAuthenticationStrategy()
        val csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse()
        val securityContexts = HttpSessionSecurityContextRepository()
    }
}
