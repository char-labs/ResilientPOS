package com.resilientpos.terminal.internal.adapter

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 같은 종류의 로그를 [LOG_INTERVAL]에 한 줄로 줄인다. 남길 차례면 그 사이 건너뛴 수와 직전 줄의 시각을, 아니면 null을 돌려준다.
 * 직전 시각이 있어야 장애 사이를 넘어 이월된 건수를 가를 수 있다. 시계가 뒤로 가면 기다리지 않고 남긴다.
 */
class LogThrottle {
    private val lastMillis = AtomicLong(NEVER)
    private val skipped = AtomicLong()

    fun skip() {
        skipped.incrementAndGet()
    }

    fun pass(nowMillis: Long): Logged? {
        val last = lastMillis.get()
        // 거절 경로는 Redis를 부르기 전 시각이라 동시 요청의 완료 순서가 뒤바뀌면 조금 음수가 된다. 간격보다 크게 뒤로 간 것만 시계 역행으로 본다.
        val interval = LOG_INTERVAL.toMillis()
        val due = last == NEVER || nowMillis - last !in -interval until interval
        if (!due || !lastMillis.compareAndSet(last, nowMillis)) {
            skip()
            return null
        }
        return Logged(skipped.getAndSet(0), last.takeIf { it != NEVER })
    }

    data class Logged(val suppressed: Long, val lastMillis: Long?) {
        override fun toString() = "suppressedSinceLast=$suppressed lastLoggedAt=${lastMillis?.let(Instant::ofEpochMilli) ?: "none"}"
    }

    private companion object {
        val LOG_INTERVAL: Duration = Duration.ofSeconds(10)
        const val NEVER = Long.MIN_VALUE
    }
}
