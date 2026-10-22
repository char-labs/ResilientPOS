package com.resilientpos.platform

/**
 * 단말이나 매장의 요청 빈도가 상한을 넘었다. 응답은 429와 `Retry-After`([retryAfterSeconds], 1 이상)이고 단말은 그 뒤 같은 요청을 다시 보낸다.
 * 메시지(어느 bucket이 거절했는지와 단말·매장 식별자)는 진단과 테스트용이고 응답에 나가지 않는다. 거절 로그는 limiter가 따로 남긴다. 토큰과 `Authorization` 값은 넣지 않는다.
 * 고장 난 단말이 반복해도 요청마다 스택 한 덩어리가 쌓이지 않게 스택 트레이스를 만들지 않는다.
 */
class RateLimitedException(val retryAfterSeconds: Long, message: String) : RuntimeException(message, null, false, false) {
    init {
        // 0이면 `Retry-After: 0`이 나가 단말이 기다리지 않고 바로 다시 보낸다.
        require(retryAfterSeconds >= 1) { "Retry-After는 1초 이상이어야 한다: $retryAfterSeconds" }
    }
}
