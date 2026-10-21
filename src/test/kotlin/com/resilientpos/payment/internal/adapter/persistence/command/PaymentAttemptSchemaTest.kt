package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.internal.domain.PaymentAttempt
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired

/**
 * 컬럼 타입과 폭을 값으로 고정한다.
 *
 * 왕복 테스트로는 고정할 수 없다. `toDomain()`이 `Money.of`로 정규화하므로 컬럼 scale을 0으로 바꿔도
 * KRW 정수 금액은 그대로 돌아온다. Hibernate의 `ddl-auto: validate`도 varchar 길이는 대조하지 않는다.
 * 그래서 `numeric(19,0)`이나 `varchar(8)`로 좁히는 변경이 조용히 지나가고 운영에서야 값이 잘린다.
 */
class PaymentAttemptSchemaTest(
    @Autowired private val dataSource: DataSource,
) : PostgresIntegrationTest() {

    @Test
    fun `t_payment_attempt의 컬럼 타입과 폭을 고정한다`() {
        val columns = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select column_name, data_type, character_maximum_length, numeric_precision, numeric_scale
                from information_schema.columns
                where table_name = 't_payment_attempt'
                order by column_name
                """.trimIndent(),
            ).executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val width = rows.getInt("character_maximum_length").takeIf { !rows.wasNull() }
                            ?: rows.getInt("numeric_precision").takeIf { !rows.wasNull() }
                                ?.let { precision -> precision * 100 + rows.getInt("numeric_scale") }
                        add(Triple(rows.getString("column_name"), rows.getString("data_type"), width))
                    }
                }
            }
        }

        assertEquals(
            listOf(
                Triple("amount", "numeric", 1904),
                Triple("checkout_no", "integer", 3200),
                Triple("created_at", "timestamp with time zone", null),
                Triple("currency", "character", 3),
                Triple("deleted_at", "timestamp with time zone", null),
                Triple("id", "uuid", null),
                Triple("kind", "character varying", 32),
                Triple("order_id", "uuid", null),
                Triple("provider_reference", "character varying", 255),
                Triple("query_count", "integer", 3200),
                Triple("reconciliation_requested_at", "timestamp with time zone", null),
                Triple("retried_from", "uuid", null),
                Triple("status", "character varying", 32),
                Triple("store_id", "uuid", null),
                Triple("terminal_id", "uuid", null),
                Triple("unknown_reason", "character varying", 32),
                Triple("updated_at", "timestamp with time zone", null),
            ),
            columns,
        )
    }

    @Test
    fun `같은 checkout에 하나뿐인 승인 상태는 REQUESTED·APPROVED·UNKNOWN이다`() {
        // APPROVED가 빠지면 승인된 checkout에 새 승인이 들어간다(이중 청구). DECLINED가 들어가면 재결제가 막힌다.
        val definition = indexDefinition("ux_payment_attempt_inflight_authorization")

        PaymentAttemptStatus.entries.forEach { status ->
            assertEquals(
                status in setOf(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.APPROVED, PaymentAttemptStatus.UNKNOWN),
                definition.contains("'${status.name}'"),
                "${status.name}이 인덱스 술어에서 기대와 다르게 다뤄진다: $definition",
            )
        }
    }

    @Test
    fun `사실로 만든 첫 승인 시도의 unique는 재결제를 빼고 삭제된 행에도 걸린다`() {
        // 멱등 키라서 deleted_at 술어를 두지 않는다. 진행 중 승인 인덱스와 모양을 맞추려고 넣으면 삭제 뒤 같은 사실이 다시 들어간다.
        val definition = indexDefinition("ux_payment_attempt_first_authorization")

        assertContains(definition, "retried_from IS NULL")
        assertFalse(definition.contains("deleted_at"), definition)
    }

    private fun indexDefinition(name: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("select pg_get_indexdef(oid) from pg_class where relname = ?").apply {
            setString(1, name)
        }.executeQuery().use { rows ->
            check(rows.next()) { "인덱스가 없다: $name" }
            rows.getString(1)
        }
    }
}
