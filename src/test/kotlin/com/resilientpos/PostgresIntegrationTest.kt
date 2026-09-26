package com.resilientpos

import java.util.concurrent.TimeUnit
import kotlin.test.fail
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 정합성 정본이 PostgreSQL이므로 통합 테스트도 같은 엔진에서 돈다. Docker가 필요하다.
 * 결제 시도 생성의 중복 완화 잠금이 Redis를 쓰고, 모듈 사이 이벤트가 Kafka를 거치므로 둘도 함께 띄운다.
 *
 * 컨테이너는 JVM 전체에서 한 번만 뜬다. JUnit의 컨테이너 생명주기를 쓰면 첫 테스트 클래스가 끝날 때
 * 컨테이너가 내려가는데 Spring은 캐시된 컨텍스트를 그대로 재사용해서 다음 클래스가 연결에 실패한다.
 * 정리는 Testcontainers의 ryuk이 JVM 종료 시 수행한다.
 *
 * 브로커의 자동 토픽 생성을 끈다. 운영과 같이 앱이 만든 토픽만 있어야 이름이 틀린 발행이 조용히 새 토픽을 만들지 않고 실패한다.
 * 캐시된 컨텍스트가 여럿 살아 있으면 같은 consumer group을 나눠 가져 다른 컨텍스트가 메시지를 처리하므로
 * `src/test/resources/spring.properties`가 컨텍스트 캐시를 하나로 제한한다.
 */
// 재시도 간격을 줄인다. 운영값(1초에서 2배씩 5회, 약 31초)이면 실패를 다루는 테스트가 그동안 파티션을 붙잡아 뒤 테스트가 시간을 넘긴다.
@SpringBootTest(properties = ["resilientpos.kafka.partitions=1", "resilientpos.kafka.retry.initial-interval=PT0.02S"])
abstract class PostgresIntegrationTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:18-alpine"
        private const val REDIS_IMAGE = "redis:8-alpine"
        private const val KAFKA_IMAGE = "apache/kafka:4.1.0"

        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE).apply { start() }

        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(REDIS_IMAGE).withExposedPorts(6379).apply { start() }

        @ServiceConnection
        @JvmStatic
        val kafka: KafkaContainer = KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .apply { start() }

        /** 커밋 뒤 이벤트는 Kafka를 거쳐 다른 스레드에서 처리되므로 결과가 보일 때까지 기다린다. */
        fun awaitUntil(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!condition()) {
                if (System.nanoTime() >= deadline) fail("20초 안에 충족되지 않았다: $description")
                Thread.sleep(20)
            }
        }
    }
}
