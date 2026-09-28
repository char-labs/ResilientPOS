package com.resilientpos

import java.time.Duration
import kotlin.test.fail
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException

private val AWAIT_AT_MOST: Duration = Duration.ofSeconds(10)
private val POLL_INTERVAL: Duration = Duration.ofMillis(20)

/**
 * 커밋 뒤에 도는 리스너처럼 비동기로 끝나는 일을 10초까지 기다린다. 넘으면 [description]으로 실패한다.
 * 기다리기는 Awaitility에 맡기고, 조건은 호출 스레드에서 곧바로 평가한다.
 * [diagnostics]는 실패할 때만 불러 원인을 좁힐 상태(발행 기록 등)를 메시지 끝에 붙인다.
 */
fun awaitUntil(description: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
    try {
        await(description)
            .atMost(AWAIT_AT_MOST)
            .pollDelay(Duration.ZERO)
            .pollInterval(POLL_INTERVAL)
            .pollInSameThread()
            .until { condition() }
    } catch (e: ConditionTimeoutException) {
        fail("${AWAIT_AT_MOST.toSeconds()}초 안에 충족되지 않았다: $description${diagnostics()}", e)
    }
}
