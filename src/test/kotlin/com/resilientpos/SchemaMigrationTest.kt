package com.resilientpos

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired

class SchemaMigrationTest(
    @Autowired private val dataSource: DataSource,
) : PostgresIntegrationTest() {

    @Test
    fun `baseline 마이그레이션이 성공으로 기록된다`() {
        val applied = query(
            "select version, success from flyway_schema_history order by installed_rank",
        ) { it.getString("version") to it.getBoolean("success") }

        assertEquals(listOf("001" to true, "002" to true, "003" to true, "004" to true, "005" to true, "006" to true, "007" to true, "008" to true, "009" to true, "010" to true), applied)
    }

    @Test
    fun `발행 기록 status 인덱스가 유효하게 만들어진다`() {
        // CONCURRENTLY는 실패해도 롤백되지 않고 INVALID 인덱스를 남긴다. 그 인덱스는 조회에 쓰이지 않고 쓰기 비용만 낸다.
        val valid = query(
            "select i.indisvalid from pg_index i join pg_class c on c.oid = i.indexrelid where c.relname = 'event_publication_status_idx'",
        ) { it.getBoolean("indisvalid") }

        assertEquals(listOf(true), valid)
    }

    @Test
    fun `테이블은 마이그레이션이 만든 것뿐이다`() {
        val tables = query(
            "select table_name from information_schema.tables where table_schema = 'public'",
        ) { it.getString("table_name") }

        assertEquals(
            listOf(
                "event_publication",
                "flyway_schema_history",
                "t_fulfillment",
                "t_order",
                "t_order_checkout",
                "t_order_line",
                "t_payment_attempt",
                "t_sync_operation",
                "t_sync_watermark",
            ),
            tables.sorted(),
        )
    }

    @Test
    fun `낙관적 잠금 version 컬럼은 그것을 선언한 엔티티의 테이블인 t_order에만 있다`() {
        val tables = query(
            "select table_name from information_schema.columns " +
                "where table_schema = 'public' and column_name = 'version' and table_name <> 'flyway_schema_history' " +
                "order by table_name",
        ) { it.getString("table_name") }

        assertEquals(listOf("t_order"), tables)
    }

    @Test
    fun `연결된 엔진은 PostgreSQL이다`() {
        dataSource.connection.use {
            assertTrue(it.metaData.databaseProductName.contains("PostgreSQL"), it.metaData.databaseProductName)
        }
    }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    generateSequence { if (rows.next()) map(rows) else null }.toList()
                }
            }
        }
}
