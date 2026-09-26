package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Moments를 끄면 Spring의 스케줄링도 함께 꺼진다. Moments의 자동 구성이 `@EnableScheduling`을 켜던 것이다(3차에 jar로 확인한 사실).
 * 갱신은 전용 스레드라 그와 무관하게 돈다. 이 컨텍스트에서는 아무도 `refresh()`를 부르지 않는다.
 */
@TestPropertySource(properties = ["spring.modulith.moments.enabled=false"])
class EventPublicationMetricsSchedulingTest(
    @Autowired private val registry: MeterRegistry,
) : PostgresIntegrationTest() {

    @Test
    fun `Moments를 꺼도 갱신은 스스로 돈다`() {
        val refreshed = registry.get("event.publication.incomplete.refreshed").gauge()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (refreshed.value() == 0.0) {
            if (System.nanoTime() >= deadline) fail("10초 안에 첫 갱신이 돌지 않았다")
            Thread.sleep(20)
        }
    }
}
