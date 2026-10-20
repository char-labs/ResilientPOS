package com.resilientpos

import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway
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

        assertEquals(listOf("001" to true, "002" to true, "003" to true, "004" to true, "005" to true, "006" to true, "007" to true, "008" to true, "009" to true, "010" to true, "011" to true, "012" to true, "013" to true, "014" to true, "015" to true, "016" to true, "017" to true), applied)
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
    fun `결제 시도 UNKNOWN 인덱스가 유효하게 만들어진다`() {
        val valid = query(
            "select i.indisvalid from pg_index i join pg_class c on c.oid = i.indexrelid where c.relname = 'ix_payment_attempt_unknown_authorization'",
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
                "t_owner",
                "t_payment_attempt",
                "t_reconciliation_case",
                "t_store",
                "t_sync_operation",
                "t_sync_watermark",
                "t_terminal",
                "t_terminal_credential",
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
    fun `V014는 기존 단말 행을 매장별 LEGACY 순번으로 채우고 NOT NULL과 unique를 건다`() {
        // 앱이 쓰는 public 스키마와 분리한 스키마에서 V013까지 적용 -> 행 삽입 -> V014 적용 순으로 돌린다.
        val schema = "v014_fill"
        fun flyway(target: String) = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).target(target).load()
        val storeA = UUID.randomUUID()
        val storeB = UUID.randomUUID()
        try {
            flyway("013").migrate()
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    listOf(storeA to "2026-10-01T00:00:01Z", storeA to "2026-10-01T00:00:02Z", storeB to "2026-10-01T00:00:03Z").forEach { (store, at) ->
                        s.executeUpdate(
                            "insert into $schema.t_terminal (id, store_id, status, created_at, updated_at) values ('${UUID.randomUUID()}', '$store', 'ACTIVE', '$at', '$at')",
                        )
                    }
                }
            }

            flyway("014").migrate()

            val filled = query("select store_id, terminal_code from $schema.t_terminal order by created_at") {
                it.getString("store_id") to it.getString("terminal_code")
            }
            assertEquals(
                listOf(storeA.toString() to "LEGACY-1", storeA.toString() to "LEGACY-2", storeB.toString() to "LEGACY-1"),
                filled,
            )
            assertEquals(
                listOf("NO"),
                query(
                    "select is_nullable from information_schema.columns " +
                        "where table_schema = '$schema' and table_name = 't_terminal' and column_name = 'terminal_code'",
                ) { it.getString("is_nullable") },
            )
            val unique = query(
                "select conname from pg_constraint where conrelid = '$schema.t_terminal'::regclass and contype = 'u'",
            ) { it.getString("conname") }
            assertEquals(listOf("ux_terminal_store_code"), unique)
        } finally {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("drop schema if exists $schema cascade") } }
        }
    }

    @Test
    fun `V016은 기존 UNKNOWN 행의 unknown_reason을 RESULT_UNCONFIRMED로 채우고 query_count는 0으로 둔다`() {
        val schema = "v016_fill"
        fun flyway(target: String) = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).target(target).load()
        val unknown = UUID.randomUUID()
        val approved = UUID.randomUUID()
        try {
            flyway("015").migrate()
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    listOf(unknown to "UNKNOWN", approved to "APPROVED").forEach { (id, status) ->
                        s.executeUpdate(
                            "insert into $schema.t_payment_attempt (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status, created_at, updated_at) " +
                                "values ('$id', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', 1, 'AUTHORIZATION', 4500, 'KRW', '$status', now(), now())",
                        )
                    }
                }
            }

            flyway("016").migrate()

            val rows = query(
                "select id, unknown_reason, query_count, reconciliation_requested_at from $schema.t_payment_attempt",
            ) { listOf(it.getString("id"), it.getString("unknown_reason"), it.getInt("query_count"), it.getTimestamp("reconciliation_requested_at")) }
                .associateBy { it[0] }
            assertEquals(listOf(unknown.toString(), "RESULT_UNCONFIRMED", 0, null), rows.getValue(unknown.toString()))
            // 이미 확정된 행은 기록 이전이라 사유를 채우지 않는다.
            assertEquals(listOf(approved.toString(), null, 0, null), rows.getValue(approved.toString()))
        } finally {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("drop schema if exists $schema cascade") } }
        }
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
