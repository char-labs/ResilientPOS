package com.resilientpos.platform

import org.slf4j.event.Level
import org.springframework.http.HttpStatus

/**
 * advice가 직접 쓰는 HTTP 공통 오류. 도메인 규칙 위반 코드는 여기에 두지 않는다.
 * `message`는 응답에 그대로 나가므로 내부 정보를 담지 않는다.
 */
enum class ErrorType(val status: HttpStatus, val message: String, val level: Level) {
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.", Level.ERROR),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다.", Level.WARN),
    NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 요청입니다.", Level.INFO),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.", Level.WARN),
    CONCURRENT_REQUEST(HttpStatus.CONFLICT, "같은 대상에 대한 다른 요청을 처리하고 있습니다. 다시 요청해 주세요.", Level.WARN),
}
