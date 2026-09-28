package com.resilientpos

import java.util.concurrent.TimeUnit
import kotlin.test.fail

private const val AWAIT_SECONDS = 10L
private const val POLL_MILLIS = 20L

/**
 * 커밋 뒤에 도는 리스너처럼 비동기로 끝나는 일을 10초까지 기다린다. 넘으면 [description]으로 실패한다.
 * [diagnostics]는 실패할 때만 불러 원인을 좁힐 상태(발행 기록 등)를 메시지 끝에 붙인다.
 */
fun awaitUntil(description: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
    while (!condition()) {
        if (System.nanoTime() >= deadline) fail("${AWAIT_SECONDS}초 안에 충족되지 않았다: $description${diagnostics()}")
        Thread.sleep(POLL_MILLIS)
    }
}
