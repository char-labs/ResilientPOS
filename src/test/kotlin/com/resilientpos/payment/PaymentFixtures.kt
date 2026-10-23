package com.resilientpos.payment

import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.TestFixtureMonkey
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderFixtures
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * `PaymentCommand` fixture. 매장·단말·통화는 [OrderFixtures]의 기본값을 따른다. 결제 결말은 FakePaymentGateway가 금액의 끝자리로 정한다.
 * 파라미터 이름과 순서는 커맨드 생성자와 같다.
 */
object PaymentFixtures {

    /** 끝자리 0이라 FakePaymentGateway가 승인하는 금액. */
    val approvedAmount: Money = Money.of("4500", OrderFixtures.krw)

    fun createAttemptCommand(
        orderId: UUID = UUID.randomUUID(),
        storeId: StoreId = OrderFixtures.storeId,
        terminalId: TerminalId = OrderFixtures.terminalId,
        checkoutNo: Int = 1,
        amount: Money = approvedAmount,
    ): PaymentCommand.CreateAttempt =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<PaymentCommand.CreateAttempt>()
            .setExp(PaymentCommand.CreateAttempt::orderId, orderId)
            .setExp(PaymentCommand.CreateAttempt::storeId, storeId)
            .setExp(PaymentCommand.CreateAttempt::terminalId, terminalId)
            .setExp(PaymentCommand.CreateAttempt::checkoutNo, checkoutNo)
            .setExp(PaymentCommand.CreateAttempt::amount, amount)
            .sample()

    fun retryAuthorizationCommand(paymentId: PaymentId, storeId: StoreId = OrderFixtures.storeId): PaymentCommand.RetryAuthorization =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<PaymentCommand.RetryAuthorization>()
            .setExp(PaymentCommand.RetryAuthorization::paymentId, paymentId)
            .setExp(PaymentCommand.RetryAuthorization::storeId, storeId)
            .sample()

    fun resolveFromReconciliationCommand(
        paymentId: PaymentId,
        conclusion: PaymentCommand.ResolveFromReconciliation.Conclusion,
        providerReference: String?,
    ): PaymentCommand.ResolveFromReconciliation =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<PaymentCommand.ResolveFromReconciliation>()
            .setExp(PaymentCommand.ResolveFromReconciliation::paymentId, paymentId)
            .setExp(PaymentCommand.ResolveFromReconciliation::conclusion, conclusion)
            .setExp(PaymentCommand.ResolveFromReconciliation::providerReference, providerReference)
            .sample()
}
