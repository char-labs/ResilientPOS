package com.resilientpos.fulfillment.internal.application

import com.resilientpos.PostgresIntegrationTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `t_fulfillment`의 unique `(order_id)`가 실제 PostgreSQL에서 두 번째 행을 거절하는지 확인한다.
 * Facade를 두 번 부르지 않고 JDBC로 직접 두 번 넣는다 — 멱등 판정 로직이 아니라 마지막 방어선인
 * DB 제약 자체를 본다.
 */
class FulfillmentUniqueOrderIntegrationTest(
    private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private fun insert(orderId: UUID) {
        jdbcTemplate.update(
            "insert into t_fulfillment (id, order_id, created_at, updated_at) values (?, ?, now(), now())",
            UUID.randomUUID(), orderId,
        )
    }

    @Test
    fun `같은 order_id로 행을 두 번 넣으면 DB가 거절한다`() {
        val orderId = UUID.randomUUID()
        insert(orderId)

        assertFailsWith<DataIntegrityViolationException> { insert(orderId) }
    }
}
