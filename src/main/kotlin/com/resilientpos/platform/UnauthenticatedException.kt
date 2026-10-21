package com.resilientpos.platform

/**
 * 요청의 인증이 실패했다. 원인(헤더 없음·형식 오류·모르는 토큰·회전된 토큰·비활성 단말, 세션 없음·틀린 비밀번호)은
 * 응답에 드러내지 않으므로 예외에도 원인을 싣지 않는다. 토큰과 `Authorization` 헤더 값은 메시지에 넣지 않는다.
 * 토큰 대입 공격이 요청마다 스택 한 덩어리를 로그에 쌓지 않도록 스택 트레이스를 만들지 않는다.
 *
 * [challenge]는 401 응답의 `WWW-Authenticate` 값이다. 단말 토큰 인증과 운영자 토큰 인증(`/admin` 아래)은 `Bearer`를 넘기고,
 * 쿠키 세션으로 인증하는 점주 경로는 기본값 `null`로 헤더를 붙이지 않는다.
 */
class UnauthenticatedException(val challenge: String? = null) : RuntimeException("unauthenticated", null, false, false) {
    companion object {
        const val BEARER = "Bearer"
    }
}
