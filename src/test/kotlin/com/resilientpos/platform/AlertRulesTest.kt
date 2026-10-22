package com.resilientpos.platform

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * 알림 규칙(`docker/prometheus/alerts.yaml`)과 그것을 싣는 Prometheus 설정을 promtool로 검사하고 단위 테스트(`alerts.test.yaml`)를 돌린다. Docker가 필요하다.
 * 이미지는 실측 때(2026-10-21) 로컬 compose의 otel-lgtm v0.37.0 안 Prometheus와 같은 버전이다. compose는 `latest`라 이미지가 바뀌면 다시 맞춘다.
 * 규칙이 쓰는 metric 이름이 앱에 있는지는 `ObservabilityConfigurationTest`가 본다.
 */
class AlertRulesTest {

    @Test
    fun `알림 규칙과 Prometheus 설정이 promtool 검사와 단위 테스트를 통과한다`() {
        GenericContainer(DockerImageName.parse(PROMETHEUS_IMAGE))
            .withCopyFileToContainer(MountableFile.forHostPath(RULES_DIR), "/rules")
            .withCreateContainerCmdModifier { it.withEntrypoint("/bin/sh") }
            // check config가 rule_files의 규칙도 검사한다.
            .withCommand("-c", "promtool check config /rules/prometheus.yaml && promtool test rules /rules/alerts.test.yaml")
            // 0이 아닌 코드로 끝나면 start()가 던지고 Testcontainers가 컨테이너 출력을 로그에 남긴다.
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
            .use { container ->
                container.start()
                assertContains(container.logs, "SUCCESS: 15 rules found")
            }
    }

    /** 규칙마다 `runbook_url`이 자기 이름의 절을 가리키고, 그 절이 runbook에 있다. GitHub는 `## 이름` 제목을 소문자 앵커로 만든다. */
    @Test
    fun `규칙의 runbook_url은 runbook의 같은 이름 절을 가리킨다`() {
        val rules = Path.of(RULES_DIR, "alerts.yaml").readText()
        val alerts = Regex("""(?m)^\s+- alert: (\S+)$""").findAll(rules).map { it.groupValues[1].lowercase() }.toSet()
        val anchors = Regex("""runbook\.md#([a-z0-9-]+)""").findAll(rules).map { it.groupValues[1] }.toSet()
        val sections = Regex("""(?m)^## (\S+)$""").findAll(Path.of(RUNBOOK).readText()).map { it.groupValues[1].lowercase() }.toSet()

        assertEquals(alerts, anchors)
        assertTrue(sections.containsAll(anchors), "runbook에 없는 절: ${anchors - sections}")
    }

    companion object {
        private const val PROMETHEUS_IMAGE = "prom/prometheus:v3.15.0"
        private const val RULES_DIR = "docker/prometheus"
        private const val RUNBOOK = "docs/wiki/runbook.md"
    }
}
