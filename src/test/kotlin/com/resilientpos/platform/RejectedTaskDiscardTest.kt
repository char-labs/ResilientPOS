package com.resilientpos.platform

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * 큐가 찬 실행기는 기본(`AbortPolicy`)이면 제출한 쪽에 `TaskRejectedException`을 던진다.
 * 이벤트 외부화는 커밋 뒤 afterCompletion에서 제출되므로 그 예외는 Spring이 ERROR 스택 트레이스로 남기고 삼킨다.
 */
class RejectedTaskDiscardTest {

    private val registry = SimpleMeterRegistry()
    private val release = CountDownLatch(1)
    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 1
    }

    @AfterTest
    fun shutdown() {
        release.countDown()
        executor.shutdown()
    }

    @Test
    fun `큐가 차면 넘친 작업을 던지지 않고 버리고 센다`() {
        PlatformConfiguration().discardRejectedTasks(registry).customize(executor)
        executor.initialize()

        executor.execute { release.await() } // 하나뿐인 스레드를 붙잡는다
        executor.execute { } // 큐의 한 자리를 채운다
        executor.execute { } // 넘친다

        assertEquals(1.0, registry.counter("task.execution.rejected").count())
    }
}
