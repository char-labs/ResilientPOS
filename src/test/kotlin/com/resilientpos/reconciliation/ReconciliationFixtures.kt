package com.resilientpos.reconciliation

import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * `ReconciliationCommand` fixture. 판단에 중요한 금액·통화·사유·시각은 고정하고 나머지(회차·조회 횟수)는 Fixture Monkey가 채운다.
 */
object ReconciliationFixtures {

    private val fixtureMonkey: FixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val krw: Currency = Currency.getInstance("KRW")
    val usd: Currency = Currency.getInstance("USD")
    val handedAt: Instant = Instant.parse("2026-10-20T00:30:00Z")
    val storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    val ownerId = OwnerId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
    val paymentId = PaymentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val orderId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val terminalId = TerminalId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
    val amount: Money = Money.of("4500", krw)

    fun open(
        reason: PaymentReconciliationReason = PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED,
        reportedAmount: Money? = null,
        reportedProviderReference: String? = null,
        unknownReason: PaymentUnknownReason = PaymentUnknownReason.RESULT_UNCONFIRMED,
    ): ReconciliationCommand.Open =
        fixtureMonkey.giveMeKotlinBuilder<ReconciliationCommand.Open>()
            .setExp(ReconciliationCommand.Open::occurredAt, handedAt)
            .setExp(ReconciliationCommand.Open::paymentId, paymentId)
            .setExp(ReconciliationCommand.Open::orderId, orderId)
            .setExp(ReconciliationCommand.Open::storeId, storeId)
            .setExp(ReconciliationCommand.Open::terminalId, terminalId)
            .setExp(ReconciliationCommand.Open::amount, amount)
            .setExp(ReconciliationCommand.Open::reason, reason)
            .setExp(ReconciliationCommand.Open::unknownReason, unknownReason)
            .setExp(ReconciliationCommand.Open::reportedAmount, reportedAmount)
            .setExp(ReconciliationCommand.Open::reportedProviderReference, reportedProviderReference)
            .sample()

    fun resolve(
        caseId: ReconciliationCaseId,
        decision: ReconciliationDecision = ReconciliationDecision.APPROVED,
        providerReference: String? = "PG-REF-1",
        approvedAmount: Money? = amount,
        note: String = "PG 가맹점 화면에서 승인 확인",
    ): ReconciliationCommand.Resolve =
        fixtureMonkey.giveMeKotlinBuilder<ReconciliationCommand.Resolve>()
            .setExp(ReconciliationCommand.Resolve::caseId, caseId)
            .setExp(ReconciliationCommand.Resolve::storeId, storeId)
            .setExp(ReconciliationCommand.Resolve::ownerId, ownerId)
            .setExp(ReconciliationCommand.Resolve::decision, decision)
            .setExp(ReconciliationCommand.Resolve::providerReference, providerReference)
            .setExp(ReconciliationCommand.Resolve::approvedAmount, approvedAmount)
            .setExp(ReconciliationCommand.Resolve::note, note)
            .sample()
}
