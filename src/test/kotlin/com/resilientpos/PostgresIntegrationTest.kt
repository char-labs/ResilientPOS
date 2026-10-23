package com.resilientpos

import com.resilientpos.fulfillment.internal.application.FulfillmentCommandFacade
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.reconciliation.ReconciliationManagement
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 정합성 정본이 PostgreSQL이므로 통합 테스트도 같은 엔진에서 돈다. Docker가 필요하다.
 * 분산 락(`platform.Lock`)이 Redis를 쓰고, 모듈 사이 이벤트가 Kafka를 거치므로 둘도 함께 띄운다.
 *
 * 컨테이너는 JVM 전체에서 한 번만 뜬다. JUnit의 컨테이너 생명주기를 쓰면 첫 테스트 클래스가 끝날 때
 * 컨테이너가 내려가는데 Spring은 캐시된 컨텍스트를 그대로 재사용해서 다음 클래스가 연결에 실패한다.
 * 정리는 Testcontainers의 ryuk이 JVM 종료 시 수행한다.
 * test fork(`build.gradle.kts`의 `maxParallelForks`)마다 따로 뜬다. 컨테이너 reuse를 켜면 fork끼리 DB와 consumer group을 같이 쓰게 된다.
 *
 * 브로커의 자동 토픽 생성을 끈다. 운영과 같이 앱이 만든 토픽만 있어야 이름이 틀린 발행이 조용히 새 토픽을 만들지 않고 실패한다.
 * 캐시된 컨텍스트가 여럿 살아 있으면 같은 consumer group을 나눠 가져 다른 컨텍스트가 메시지를 처리하므로
 * `src/test/resources/spring.properties`가 컨텍스트 캐시를 하나로 제한한다.
 *
 * 하위 클래스마다 구성이 다르면 컨텍스트를 따로 띄운다. 그래서 다른 테스트에 무해한 구성은 여기에 모은다.
 * MockMvc와, 장애 주입에 쓰는 spy 빈이다. spy는 스텁하지 않으면 실제 동작을 타고, 테스트가 끝날 때마다 초기화된다(`MockReset.AFTER`).
 * 테스트는 이 빈을 주입받아 쓰고, 하위 클래스에서 `@MockitoBean`·`@MockitoSpyBean`을 새로 선언하지 않는다.
 */
// 재시도 간격을 줄인다. 운영값(1초에서 2배씩 5회, 약 31초)이면 실패를 다루는 테스트가 그동안 파티션을 붙잡아 뒤 테스트가 시간을 넘긴다.
// 발행 기록 재전송·정리는 하루 뒤로 미룬다. 테스트가 DB를 함께 쓰며 넣은 FAILED·완료 행을 작업이 건드리면 결과가 실행 순서에 따라 바뀐다.
// 결과 불명 결제 확정도 하루 뒤로 미룬다. 작업이 스스로 돌면 끝자리 2인 기본 픽스처 주문이 테스트 도중 PAID가 된다.
@SpringBootTest(
    properties = [
        "resilientpos.kafka.partitions=1",
        "resilientpos.kafka.retry.initial-interval=PT0.02S",
        "resilientpos.event-publication.resubmit-interval=P1D",
        "resilientpos.payment.unknown-resolve-interval=P1D",
        "resilientpos.payment.unknown-query-limit=3",
    ],
)
@AutoConfigureMockMvc
@MockitoSpyBean(types = [FulfillmentCommandFacade::class, OrderCommandFacade::class, ReconciliationManagement::class])
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
    }
}
