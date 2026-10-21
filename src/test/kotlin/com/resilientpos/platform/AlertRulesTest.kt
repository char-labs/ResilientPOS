package com.resilientpos.platform

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * 알림 규칙(`docker/prometheus/alerts.yaml`)을 promtool로 검사하고 단위 테스트(`alerts.test.yaml`)를 돌린다. Docker가 필요하다.
 * 이미지는 로컬 compose의 lgtm 안 Prometheus와 같은 버전이다. 규칙이 쓰는 metric 이름이 앱에 있는지는 `ObservabilityConfigurationTest`가 본다.
 */
class AlertRulesTest {

    @Test
    fun `알림 규칙이 promtool 검사와 단위 테스트를 통과한다`() {
        GenericContainer(DockerImageName.parse(PROMETHEUS_IMAGE))
            .withCopyFileToContainer(MountableFile.forHostPath(RULES_DIR), "/rules")
            .withCreateContainerCmdModifier { it.withEntrypoint("/bin/sh") }
            .withCommand("-c", "promtool check rules /rules/alerts.yaml && promtool test rules /rules/alerts.test.yaml")
            // 0이 아닌 코드로 끝나면 start()가 던진다. 실패 이유는 컨테이너 로그에 있다.
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
            .use { container ->
                container.start()
                assertContains(container.logs, "SUCCESS: 13 rules found")
            }
    }

    companion object {
        private const val PROMETHEUS_IMAGE = "prom/prometheus:v3.15.0"
        private const val RULES_DIR = "docker/prometheus"
    }
}
