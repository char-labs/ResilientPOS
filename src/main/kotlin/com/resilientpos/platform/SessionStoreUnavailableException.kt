package com.resilientpos.platform

/**
 * 점주 인증이 쓰는 Redis(세션·로그인 시도 카운터)에 닿지 못해 점주 요청을 처리하지 못했다. 원인은 `DataAccessException`이거나 종료 중이라 닫힌 연결의 `IllegalStateException`이다.
 * 응답 문구는 [ErrorType.SERVICE_UNAVAILABLE]의 고정 문구다.
 */
class SessionStoreUnavailableException(cause: RuntimeException) : RuntimeException("session store unavailable", cause)
