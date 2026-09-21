package com.resilientpos.platform

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
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
}
