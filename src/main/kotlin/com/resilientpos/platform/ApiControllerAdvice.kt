package com.resilientpos.platform

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * `basePackages`를 걸지 않는다. 걸면 handler가 정해지기 전에 나는 404·405에 적용되지 않는다.
 * 응답 메시지는 `ErrorType`의 고정 문구이고 예외 메시지는 로그에만 남긴다. 응답용 문구를 가진 `DomainException`만 예외다.
 */
@RestControllerAdvice
class ApiControllerAdvice : ResponseEntityExceptionHandler() {

    /** Spring MVC 표준 예외는 상태 코드를 유지하고 본문만 envelope로 바꾼다. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errorType = when {
            statusCode.value() == ErrorType.NOT_FOUND.status.value() -> ErrorType.NOT_FOUND
            statusCode.value() == ErrorType.METHOD_NOT_ALLOWED.status.value() -> ErrorType.METHOD_NOT_ALLOWED
            statusCode.is4xxClientError -> ErrorType.BAD_REQUEST
            else -> ErrorType.INTERNAL_ERROR
        }
        return super.handleExceptionInternal(ex, failBody(errorType, ex), headers, statusCode, request)
    }

    /** 도메인 규칙 위반은 예외의 `code`와 응답용 `message`를 그대로 내보내고 `kind`가 상태 코드를 정한다. */
    @ExceptionHandler(DomainException::class)
    fun handleDomain(e: DomainException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("errorCode={} : {}", e.code, e.message)
        return ResponseEntity.status(e.kind.toHttpStatus()).body(ApiResponse.fail(ErrorResponse(e.code, e.message)))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.INTERNAL_ERROR.status).body(failBody(ErrorType.INTERNAL_ERROR, e))

    // else를 두지 않는다. `DomainErrorKind`에 항목을 더하고 매핑을 빠뜨리면 컴파일되지 않는다.
    private fun DomainErrorKind.toHttpStatus(): HttpStatus = when (this) {
        DomainErrorKind.INVALID -> HttpStatus.BAD_REQUEST
        DomainErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
        DomainErrorKind.CONFLICT -> HttpStatus.CONFLICT
        DomainErrorKind.UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT
    }

    private fun failBody(errorType: ErrorType, e: Exception): ApiResponse<Nothing> {
        log.atLevel(errorType.level).setCause(e).log("errorCode={} : {}", errorType.name, e.message)
        return ApiResponse.fail(ErrorResponse(errorType.name, errorType.message))
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApiControllerAdvice::class.java)
    }
}
