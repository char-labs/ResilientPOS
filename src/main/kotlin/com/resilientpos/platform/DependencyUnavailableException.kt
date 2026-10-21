package com.resilientpos.platform

/**
 * 요청을 처리하는 데 필요한 외부 의존(운영자 IdP, Kafka 브로커 등)에 닿지 못했다. [dependency]는 로그에서 원인을 가리는 짧은 이름이다(예: `operator IdP`).
 * 응답 문구는 [ErrorType.SERVICE_UNAVAILABLE]의 고정 문구이고 원인은 로그에만 남는다. 원인 메시지에 토큰·요청 값을 담지 않는 예외만 넣는다.
 */
class DependencyUnavailableException(dependency: String, cause: Throwable) : RuntimeException("$dependency unavailable", cause)
