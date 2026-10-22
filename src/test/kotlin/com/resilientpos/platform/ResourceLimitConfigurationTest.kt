package com.resilientpos.platform

import com.zaxxer.hikari.HikariDataSource
import io.lettuce.core.ClientOptions
import jakarta.persistence.EntityManagerFactory
import java.time.Duration
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
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
        // 유휴 연결이 RST 없이 끊긴 것을 1분 안에 알아챈다(13초 쉬면 13초 간격 3회). Lettuce 기본값이고 Boot의 SocketOptions도 그대로 둔다.
        // 값은 확장 keepalive(JDK 11+ NIO)일 때만 적용되고 아니면 OS 기본값(Linux 2시간)이다. Lettuce를 올려 기본값이 바뀌면 여기서 드러난다.
        val keepAlive = options.socketOptions.keepAlive
        assertTrue(keepAlive.isEnabled)
        assertEquals(Duration.ofSeconds(13), keepAlive.idle)
        assertEquals(Duration.ofSeconds(13), keepAlive.interval)
        assertEquals(3, keepAlive.count)
    }

    @Test
    fun `Lettuce가 queue 초과로 거절할 때마다 남기는 WARN은 끈다`() {
        // 실패는 호출자(빈도 상한·세션·잠금)가 남긴다. 이 로거의 WARN은 이 한 종류뿐이다(lettuce-core 7.5.2 DefaultEndpoint.RetryListener).
        assertFalse(LoggerFactory.getLogger("io.lettuce.core.protocol.DefaultEndpoint").isWarnEnabled)
    }
}
