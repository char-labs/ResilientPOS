package com.resilientpos.fulfillment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.checkout
import java.util.UUID
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** checkout부터 결제 승인, 주문 `PAID`, fulfillment 생성까지 리스너 셋이 잇따라 도는 흐름. */
class FulfillmentFlowIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private fun fulfillments(orderId: UUID) =
        jdbcTemplate.queryForList("select id from t_fulfillment where order_id = ?", orderId)

    @Test
    fun `checkout부터 fulfillment 생성까지 한 흐름이 돈다`() {
        // 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다.
        val orderId = orderFacade.checkout(OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line("4500"))))

        awaitUntil("fulfillment가 생긴다") { fulfillments(orderId.value).size == 1 }
    }
}
