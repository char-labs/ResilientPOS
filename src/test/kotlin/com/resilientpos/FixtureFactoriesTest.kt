package com.resilientpos

import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.owner.OwnerCommand
import com.resilientpos.owner.OwnerFixtures
import com.resilientpos.owner.OwnerId
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentFixtures
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreCommand
import com.resilientpos.store.StoreFixtures
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.application.SyncCommand
import com.resilientpos.sync.internal.application.SyncFixtures
import com.resilientpos.sync.internal.domain.CheckoutPayload
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalCommand
import com.resilientpos.terminal.TerminalFixtures
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 모듈 Fixture의 커맨드 factory가 넘긴 값을 그대로 쓰는지 본다. factory는 Fixture Monkey로 만들어서,
 * 값을 분해했다 다시 조립하는 동안 null이나 중첩된 값이 임의 값으로 바뀌면 그 커맨드를 쓰는 테스트가 엉뚱한 입력으로 돈다.
 */
class FixtureFactoriesTest {

    private val orderId = OrderId(UUID.randomUUID())
    private val paymentId = PaymentId(UUID.randomUUID())
    private val storeId = StoreId(UUID.randomUUID())
    private val terminalId = TerminalId(UUID.randomUUID())
    private val clientOrderId = ClientOrderId(UUID.randomUUID())

    @Test
    fun `주문 factory는 생성자와 같은 커맨드를 만든다`() {
        val line = OrderCommand.Create.Line("SKU-9", "콜드브루", Money.of("4500", OrderFixtures.krw), 2)

        assertEquals(line, OrderFixtures.line("4500", quantity = 2, productCode = "SKU-9", productName = "콜드브루"))
        assertEquals(
            OrderCommand.Create(storeId, terminalId, clientOrderId, OrderFixtures.krw, listOf(line), occurredAt = null),
            OrderFixtures.createOrderCommand(storeId, terminalId, clientOrderId, OrderFixtures.krw, listOf(line)),
        )
        assertEquals(OrderCommand.RequestCheckout(orderId, storeId, terminalId), OrderFixtures.requestCheckoutCommand(orderId, storeId, terminalId))
        assertEquals(
            OrderCommand.RequestCheckoutByClientOrderId(terminalId, clientOrderId, BigDecimal("3502")),
            OrderFixtures.requestCheckoutByClientOrderIdCommand(terminalId, clientOrderId, BigDecimal("3502")),
        )
    }

    @Test
    fun `결제 factory는 null 참조까지 생성자와 같은 커맨드를 만든다`() {
        val amount = Money.of("4501", OrderFixtures.krw)

        assertEquals(
            PaymentCommand.CreateAttempt(orderId.value, storeId, terminalId, 2, amount),
            PaymentFixtures.createAttemptCommand(orderId.value, storeId, terminalId, 2, amount),
        )
        assertEquals(PaymentCommand.RetryAuthorization(paymentId, storeId), PaymentFixtures.retryAuthorizationCommand(paymentId, storeId))
        val declined = PaymentCommand.ResolveFromReconciliation.Conclusion.DECLINED
        assertEquals(
            PaymentCommand.ResolveFromReconciliation(paymentId, declined, null),
            PaymentFixtures.resolveFromReconciliationCommand(paymentId, declined, null),
        )
    }

    @Test
    fun `점주·매장·단말 factory는 생성자와 같은 커맨드를 만든다`() {
        val ownerId = OwnerId(UUID.randomUUID())
        val code = TerminalCode("T-FIXTURE")

        assertEquals(OwnerCommand.SignUp("owner@example.com", "pw-1234567890"), OwnerFixtures.signUpCommand("owner@example.com", "pw-1234567890"))
        assertEquals(OwnerCommand.LogIn("owner@example.com", "pw-1234567890"), OwnerFixtures.logInCommand("owner@example.com", "pw-1234567890"))
        assertEquals(StoreCommand.Create(ownerId, "  강남점 "), StoreFixtures.createStoreCommand(ownerId, "  강남점 "))
        assertEquals(TerminalCommand.Register(storeId, code), TerminalFixtures.registerCommand(storeId, code))
        assertEquals(TerminalCommand.RotateCredential(storeId, terminalId), TerminalFixtures.rotateCredentialCommand(storeId, terminalId))
        assertEquals(TerminalCommand.Deactivate(storeId, terminalId), TerminalFixtures.deactivateCommand(storeId, terminalId))
    }

    @Test
    fun `sync factory는 중첩된 operation의 null과 payload 종류를 그대로 둔다`() {
        val operations = listOf(
            SyncCommand.ReceiveBatch.Operation(
                UUID.randomUUID(),
                1,
                OrderPayload(storeId, clientOrderId, "KRW", listOf(OrderPayload.Line("SKU-1", "아메리카노", BigDecimal("1001"), BigDecimal("2")))),
                null,
            ),
            SyncCommand.ReceiveBatch.Operation(UUID.randomUUID(), 2, CheckoutPayload(clientOrderId, BigDecimal("2002")), "2026-10-23T00:00:00Z"),
        )

        assertEquals(SyncCommand.ReceiveBatch(terminalId, storeId, operations), SyncFixtures.receiveBatchCommand(terminalId, storeId, operations))
    }
}
