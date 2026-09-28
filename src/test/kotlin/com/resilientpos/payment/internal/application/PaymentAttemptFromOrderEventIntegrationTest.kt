package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.order.checkout
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `order`가 낸 사실이 `payment`의 행이 되는 경로를 끝까지 태운다.
 * 리스너가 비동기라 조건이 만족될 때까지 짧게 반복해서 기다린다.
 */
class PaymentAttemptFromOrderEventIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private fun attempts(orderId: UUID) = jdbcTemplate.queryForList(
        "select id, kind, status, amount, currency, checkout_no from t_payment_attempt where order_id = ?",
        orderId,
    )

    private fun checkoutOrder(lines: List<OrderCommand.Create.Line>): UUID =
        orderFacade.checkout(OrderFixtures.createOrderCommand(lines = lines)).value

    @Test
    fun `checkout을 요청하면 결제 시도가 하나 기록된다`() {
        val orderId = checkoutOrder(listOf(OrderFixtures.line("4500", 2)))

        // 시도가 기록된 뒤 승인 리스너가 이어서 돌므로 결말까지 기다린다. 기록된 직후를 보면 두 상태 사이에서 흔들린다.
        // 어느 결말인지는 묻지 않는다 — 이 테스트의 주제는 order의 사실이 payment의 행이 되는 것이다.
        awaitUntil("결제 시도가 결말까지 간다") { attempts(orderId).singleOrNull()?.get("status").let { it != null && it != "REQUESTED" } }

        val row = attempts(orderId).single()
        assertEquals("AUTHORIZATION", row["kind"])
        assertEquals("KRW", (row["currency"] as String).trim())
        assertEquals(1, row["checkout_no"])
        assertEquals(0, (row["amount"] as java.math.BigDecimal).compareTo(java.math.BigDecimal("9000")))
    }

    @Test
    fun `0원 주문의 시도는 승인으로 기록된다`() {
        val orderId = checkoutOrder(listOf(OrderCommand.Create.Line("SKU-FREE", "증정품", Money.of("0", OrderFixtures.krw), 1)))

        awaitUntil("0원 시도가 승인으로 기록된다") {
            attempts(orderId).singleOrNull()?.get("status") == "APPROVED"
        }
    }

    @Test
    fun `같은 checkout으로 다시 부르면 기존 시도를 그대로 돌려준다`() {
        val orderId = checkoutOrder(listOf(OrderFixtures.line("4500")))
        // 결말까지 기다린 뒤에 다시 부른다. 진행 중에 부르면 돌려받는 상태가 그때그때 달라진다.
        awaitUntil("첫 시도가 결말까지 간다") { attempts(orderId).singleOrNull()?.get("status").let { it != null && it != "REQUESTED" } }
        val first = attempts(orderId).single()

        // 리스너가 같은 이벤트를 다시 받은 것과 같은 상황이다.
        val again = paymentManagement.createAttempt(
            PaymentCommand.CreateAttempt(
                orderId = orderId,
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = 1,
                amount = Money.of("4500", OrderFixtures.krw),
            ),
        )

        assertEquals(1, attempts(orderId).size)
        assertEquals(first["id"], again.paymentId.value)
        assertEquals(first["status"], again.status.name)
    }

    @Test
    fun `거절된 시도가 있는 checkout에 같은 요청이 다시 와도 새 시도를 만들지 않는다`() {
        // 4501원은 끝자리가 1이라 Fake가 거절한다.
        val orderId = checkoutOrder(listOf(OrderFixtures.line("4501")))
        awaitUntil("시도가 거절까지 간다") { attempts(orderId).singleOrNull()?.get("status") == "DECLINED" }
        val declined = attempts(orderId).single()["id"]

        // 같은 OrderPaymentRequested가 재배달된 것과 같은 상황이다. 재결제 의도가 아니라 같은 사실이다.
        val again = paymentManagement.createAttempt(
            PaymentCommand.CreateAttempt(
                orderId = orderId,
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = 1,
                amount = Money.of("4501", OrderFixtures.krw),
            ),
        )

        assertEquals(1, attempts(orderId).size)
        assertEquals(declined, again.paymentId.value)
        assertEquals(PaymentAttemptStatus.DECLINED, again.status)
    }

    @Test
    fun `다른 주문의 시도는 서로 섞이지 않는다`() {
        val first = checkoutOrder(listOf(OrderFixtures.line("4500")))
        val second = checkoutOrder(listOf(OrderFixtures.line("4500")))

        awaitUntil("두 주문 모두 시도가 기록된다") { attempts(first).size == 1 && attempts(second).size == 1 }

        assertNotEquals(attempts(first).single()["id"], attempts(second).single()["id"])
        assertEquals(PaymentAttemptKind.AUTHORIZATION.name, attempts(second).single()["kind"])
    }
}
