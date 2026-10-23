package com.resilientpos.platform

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import io.github.oshai.kotlinlogging.Level
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
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
        // 본문 변환 실패 메시지는 입력 조각(예: 따옴표 없는 비밀번호)을 담으므로 메시지와 스택 없이 클래스 이름만 남긴다.
        val body = if (ex is HttpMessageNotReadableException) {
            failBody(errorType, null, "${request.describe()} cause=${ex.javaClass.simpleName}")
        } else {
            failBody(errorType, ex, request.describe())
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request)
    }

    /**
     * 도메인 규칙 위반은 예외의 `code`와 응답용 `message`를 그대로 내보내고 `kind`가 상태 코드를 정한다.
     * 도메인 404는 없는 경로의 404([ErrorType.NOT_FOUND])와 같은 수준으로 남긴다. 다른 매장 ID를 훑는 요청이 WARN으로 쌓이지 않게 한다.
     */
    @ExceptionHandler(DomainException::class)
    fun handleDomain(e: DomainException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val level = if (e.kind == DomainErrorKind.NOT_FOUND) ErrorType.NOT_FOUND.level else Level.WARN
        log.at(level, marker = null) { message = "${request.describe()} errorCode=${e.code} : ${e.message}" }
        return ResponseEntity.status(e.kind.toHttpStatus()).body(ApiResponse.fail(ErrorResponse(e.code, e.message)))
    }

    /**
     * 원인에 관계없이 같은 응답이다. 원인을 나누면 토큰·계정 추측에 단서를 준다.
     * `WWW-Authenticate`는 예외의 challenge가 있을 때만 붙인다(단말 토큰과 운영자 토큰은 `Bearer`, 쿠키 세션인 점주 경로는 없다).
     */
    @ExceptionHandler(UnauthenticatedException::class)
    fun handleUnauthenticated(e: UnauthenticatedException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.UNAUTHENTICATED.status)
            .headers { headers -> e.challenge?.let { headers.set(HttpHeaders.WWW_AUTHENTICATE, it) } }
            .body(failBody(ErrorType.UNAUTHENTICATED, e, request.describe()))

    /**
     * 단말이나 매장의 요청 빈도가 상한을 넘었거나 같은 단말의 요청이 이미 처리 중이다(동시 상한). 어느 상한인지 구분하지 않고 같은 응답이다.
     * 단말은 `Retry-After`(정수 초)만큼 기다린 뒤 같은 요청을 그대로 다시 보낸다.
     * 로그는 남기지 않는다. limiter가 scope별 10초에 한 줄로 남긴다. 여기서 요청마다 남기면 `Retry-After`를 무시하는 단말이 로그를 요청 수만큼 늘린다.
     */
    @ExceptionHandler(RateLimitedException::class)
    fun handleRateLimited(e: RateLimitedException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.RATE_LIMITED.status)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            .body(ApiResponse.fail(ErrorResponse(ErrorType.RATE_LIMITED.name, ErrorType.RATE_LIMITED.message)))

    /**
     * 점주 경로의 CSRF 토큰이 없거나 쿠키와 다르다(`CsrfException`). 운영자 토큰에 운영자 권한이 없을 때([AdminSecurityFilterChain])도 여기로 온다.
     * `InvalidCsrfTokenException`의 메시지에 제출된 토큰 값이 실리므로 예외 메시지와 스택은 로그에도 남기지 않고 code만 남긴다.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.ACCESS_DENIED.status)
            .body(failBody(ErrorType.ACCESS_DENIED, null, request.describe()))

    /**
     * 같은 대상에 요청이 겹쳐서 난 잠금 대기 초과, 버전 충돌, 같은 키의 동시 생성(커밋을 소유한 Facade가 제약 이름으로 가려 이 계열로 바꾼다). 서버 오류로 집계하지 않는다.
     * 다시 보내도 되는지는 endpoint가 멱등인지에 달려 있어서 문구로 약속하지 않는다.
     */
    @ExceptionHandler(ConcurrencyFailureException::class)
    fun handleConcurrency(e: ConcurrencyFailureException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.CONCURRENT_REQUEST.status)
            .body(failBody(ErrorType.CONCURRENT_REQUEST, e, request.describe()))

    /** 세션 저장소(Redis) 장애. 원인 예외는 로그에만 남기고 응답은 고정 문구다. */
    @ExceptionHandler(SessionStoreUnavailableException::class)
    fun handleSessionStoreUnavailable(e: SessionStoreUnavailableException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.SERVICE_UNAVAILABLE.status)
            .body(failBody(ErrorType.SERVICE_UNAVAILABLE, e, request.describe()))

    /** 외부 의존(운영자 IdP 등) 장애. 원인 예외는 로그에만 남기고 응답은 고정 문구다. */
    @ExceptionHandler(DependencyUnavailableException::class)
    fun handleDependencyUnavailable(e: DependencyUnavailableException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.SERVICE_UNAVAILABLE.status)
            .body(failBody(ErrorType.SERVICE_UNAVAILABLE, e, request.describe()))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ErrorType.INTERNAL_ERROR.status)
            .body(failBody(ErrorType.INTERNAL_ERROR, e, request.describe()))

    // else를 두지 않는다. `DomainErrorKind`에 항목을 더하고 매핑을 빠뜨리면 컴파일되지 않는다.
    private fun DomainErrorKind.toHttpStatus(): HttpStatus = when (this) {
        DomainErrorKind.INVALID -> HttpStatus.BAD_REQUEST
        DomainErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
        DomainErrorKind.CONFLICT -> HttpStatus.CONFLICT
        DomainErrorKind.UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT
        DomainErrorKind.FORBIDDEN -> HttpStatus.FORBIDDEN
    }

    private fun failBody(errorType: ErrorType, e: Exception?, request: String): ApiResponse<Nothing> {
        // marker를 생략하면 기본 인자 브리지(`KLogger.at$default`)를 거쳐 로그의 호출 위치가 그 브리지로 찍힌다.
        log.at(errorType.level, marker = null) {
            message = "$request errorCode=${errorType.name}" + (e?.let { " : ${it.message}" } ?: "")
            cause = e
        }
        return ApiResponse.fail(ErrorResponse(errorType.name, errorType.message))
    }

    /**
     * 로그 한 줄로 요청을 특정하기 위한 최소 정보. 경로 변수가 URI에 그대로 있어서
     * `/v1/orders/{orderId}/checkout`의 주문 식별자는 URI만으로 드러난다. advice가 endpoint별로
     * 식별자를 뽑으려 들면 endpoint가 늘 때마다 여기를 고쳐야 한다.
     * 쿼리 문자열은 넣지 않는다. 개인정보가 로그로 흘러 들어가는 경로가 된다.
     */
    private fun HttpServletRequest.describe(): String = "method=$method uri=$requestURI"

    /**
     * MVC는 여기에 늘 `ServletWebRequest`를 넘기지만 타입이 `WebRequest`라 단정할 수 없다.
     * 단언으로 깨면 예외를 처리하는 자리에서 새 예외가 나므로, 형식을 유지한 채 값만 비운다.
     */
    private fun WebRequest.describe(): String =
        (this as? ServletWebRequest)?.request?.describe() ?: "method=? uri=?"

    companion object {
        private val log by logger()
    }
}
