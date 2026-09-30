package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.IllegalTransactionStateException

/**
 * 호출자 트랜잭션에 합류하는 생성 경로가 트랜잭션 밖에서 불리면 **저장소를 건드리기 전에** 거절하는지 본다. Docker가 필요하다.
 * 거절하지 않으면 주문 행만 자기 트랜잭션으로 커밋되고 호출자가 묶으려던 한 커밋이 조용히 둘로 갈라진다.
 * 검사가 `TxRunner`의 전파 속성(`MANDATORY`)이라 프록시가 있는 실제 컨텍스트에서 본다.
 */
class OrderCreateInCallerTransactionIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    @Test
    fun `트랜잭션 밖에서 부르면 저장소를 건드리기 전에 거절한다`() {
        val command = OrderFixtures.createOrderCommand()

        assertFailsWith<IllegalTransactionStateException> { facade.createInCallerTransaction(command) }

        assertEquals(
            0L,
            jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", command.clientOrderId.value),
        )
    }
}
