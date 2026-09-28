package com.resilientpos.platform

/** [Lock]이 쓰는 잠금 저장소. 직접 주입해 쓰지 않는다. */
interface DistributedLock {

    /** 잠그고 토큰을 돌려준다. 대기 한도를 넘기거나 저장소가 응답하지 않으면 던지지 않고 null이다. */
    fun tryAcquire(key: String): String?

    /** 토큰이 자기 것일 때만 푼다. 실패해도 던지지 않는다 — 커밋 뒤라 던지면 호출자가 성공을 실패로 본다. */
    fun release(key: String, token: String)
}
