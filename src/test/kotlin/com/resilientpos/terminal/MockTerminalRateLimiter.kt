package com.resilientpos.terminal

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.terminal.internal.adapter.redis.RedisTerminalRateLimiter
import com.resilientpos.terminal.internal.adapter.web.TerminalInFlightLimiter
import org.mockito.BDDMockito.willThrow
import org.springframework.context.ApplicationContext
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 웹 슬라이스 테스트는 경로와 관계없이 단말 인증 인터셉터를 싣고, 인터셉터는 동시 상한(in-flight limiter)과 빈도 상한(limiter)을 받는다. 이 애너테이션이 둘을 mock으로 넣는다.
 * 두 mock의 기본 동작(아무것도 하지 않음)은 "통과"라 기존 테스트의 의미가 바뀌지 않는다.
 * 다른 모듈의 테스트가 terminal 내부 타입을 import하지 않게 여기 둔다. 거절을 흉내 내려면 [rejectByRateLimit]를 쓴다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MockitoBean(types = [RedisTerminalRateLimiter::class, TerminalInFlightLimiter::class])
annotation class MockTerminalRateLimiter

/** [MockTerminalRateLimiter]가 넣은 limiter가 [terminal]의 요청을 단말 bucket에서 거절하게 한다. */
fun ApplicationContext.rejectByRateLimit(terminal: AuthenticatedTerminal, retryAfterSeconds: Long) {
    val message = "scope=terminal terminalId=${terminal.terminalId.value} storeId=${terminal.storeId.value}"
    willThrow(RateLimitedException(retryAfterSeconds, message)).given(getBean(RedisTerminalRateLimiter::class.java)).acquire(terminal)
}
