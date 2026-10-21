package com.resilientpos.platform

import com.zaxxer.hikari.HikariDataSource
import io.lettuce.core.ClientOptions
import jakarta.persistence.EntityManagerFactory
import java.time.Duration
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import com.resilientpos.PostgresIntegrationTest
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper

/**
 * `application.yaml`에 적은 상한이 실제 빈에 반영되는지 본다.
 * 프로퍼티 이름이 틀리면 Spring은 조용히 무시하므로, 적었다는 사실만으로는 적용됐다고 말할 수 없다.
 */
class ResourceLimitConfigurationTest(
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val dataSource: DataSource,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val redisConnectionFactory: LettuceConnectionFactory,
) : PostgresIntegrationTest() {

    @Test
    fun `요청 본문을 읽는 양에 상한이 걸려 있다`() {
        val constraints = mapper.tokenStreamFactory().streamReadConstraints()

        assertEquals(1_048_576L, constraints.maxDocumentLength, "max-document-length")
        assertEquals(65_536, constraints.maxStringLength, "max-string-length")
        assertEquals(1_000, constraints.maxNumberLength, "max-number-length")
    }

    @Test
    fun `커넥션을 기다리는 시간에 상한이 걸려 있다`() {
        val hikari = dataSource as HikariDataSource

        assertEquals(3_000, hikari.connectionTimeout)
        assertEquals(1_000, hikari.validationTimeout)
        assertEquals("3", hikari.dataSourceProperties.getProperty("connectTimeout"))
        assertEquals("30", hikari.dataSourceProperties.getProperty("socketTimeout"))
    }

    @Test
    fun `쿼리와 트랜잭션이 도는 시간에 상한이 걸려 있다`() {
        assertEquals(
            "10000",
            entityManagerFactory.properties["jakarta.persistence.query.timeout"].toString(),
        )
        assertEquals(30, (transactionManager as AbstractPlatformTransactionManager).defaultTimeout)
    }

    @Test
    fun `Redis 명령은 끊긴 연결에서 바로 거절되고 응답 없는 명령은 1000개까지만 받으며 connect timeout과 keepalive는 그대로다`() {
        val options = redisConnectionFactory.clientConfiguration.clientOptions.orElseThrow()

        assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, options.disconnectedBehavior)
        assertEquals(1_000, options.requestQueueSize)
        assertEquals(Duration.ofSeconds(1), options.socketOptions.connectTimeout)
        // 유휴 연결이 RST 없이 끊긴 것을 1분 안에 알아챈다. Lettuce 기본값이고 Boot의 SocketOptions도 그대로 둔다.
        assertTrue(options.socketOptions.keepAlive.isEnabled)
    }
}
