package com.resilientpos.platform

/**
 * 요청의 인증이 실패했다. 원인(헤더 없음·형식 오류·모르는 토큰·회전된 토큰·비활성 단말)은 응답에 드러내지 않으므로
 * 예외에도 원인을 싣지 않는다. 토큰과 `Authorization` 헤더 값은 메시지에 넣지 않는다.
 */
class UnauthenticatedException : RuntimeException("unauthenticated")
