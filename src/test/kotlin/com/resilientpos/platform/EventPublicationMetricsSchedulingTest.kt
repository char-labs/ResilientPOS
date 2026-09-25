package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.context.TestPropertySource

/**
 * Moments의 자동 구성도 스케줄링을 켜서, 거기에 기대면 Moments를 끌 때 갱신도 멈춘다.
 * `PlatformConfiguration`이 스케줄링을 직접 켜므로 Moments를 꺼도 갱신은 스케줄된다.
 */
@TestPropertySource(properties = ["spring.modulith.moments.enabled=false"])
class EventPublicationMetricsSchedulingTest(
    @Autowired private val scheduledTasks: ObjectProvider<ScheduledTaskHolder>,
) : PostgresIntegrationTest() {

    @Test
    fun `Moments를 꺼도 갱신은 1분 주기로 스케줄된다`() {
        val tasks = scheduledTasks.flatMap { it.scheduledTasks }.map { it.task }
        assertTrue(tasks.none { "Moments" in it.toString() }, "Moments가 꺼지지 않았다: $tasks")
        assertTrue(
            tasks.any {
                it is FixedDelayTask && it.intervalDuration == Duration.ofMinutes(1) &&
                    it.toString() == "${EventPublicationMetrics::class.java.name}.refresh"
            },
            "스케줄된 작업: $tasks",
        )
    }
}
