package com.resilientpos.platform

/**
 * 모든 HTTP 응답의 공통 모양. 성공은 `data`, 실패는 `error`만 채운다.
 */
data class ApiResponse<T>(
    val result: ApiResponseResult,
    val data: T? = null,
    val error: ErrorResponse? = null,
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(ApiResponseResult.SUCCESS, data)

        fun success(): ApiResponse<Nothing> = ApiResponse(ApiResponseResult.SUCCESS)

        fun fail(errorResponse: ErrorResponse): ApiResponse<Nothing> =
            ApiResponse(result = ApiResponseResult.FAIL, error = errorResponse)
    }
}

enum class ApiResponseResult {
    SUCCESS,
    FAIL,
}

/**
 * `errorCode`는 클라이언트 분기와 로그 `errorCode`에 같이 쓰는 안정적인 식별자다.
 */
data class ErrorResponse(
    val errorCode: String,
    val message: String,
)
