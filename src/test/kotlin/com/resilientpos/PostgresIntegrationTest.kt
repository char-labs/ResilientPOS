package com.resilientpos

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 정합성 정본이 PostgreSQL이므로 통합 테스트도 같은 엔진에서 돈다. Docker가 필요하다.
 *
 * 컨테이너는 JVM 전체에서 한 번만 뜬다. JUnit의 컨테이너 생명주기를 쓰면 첫 테스트 클래스가 끝날 때
 * 컨테이너가 내려가는데 Spring은 캐시된 컨텍스트를 그대로 재사용해서 다음 클래스가 연결에 실패한다.
 * 정리는 Testcontainers의 ryuk이 JVM 종료 시 수행한다.
 */
@SpringBootTest
abstract class PostgresIntegrationTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:18-alpine"

        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE).apply { start() }
    }
}
