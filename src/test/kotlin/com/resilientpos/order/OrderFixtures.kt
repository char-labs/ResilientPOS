package com.resilientpos.order

import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.Currency
import java.util.UUID

/**
 * `CreateOrderCommand` fixture. 판단에 중요한 통화·line 단가·수량은 고정하고
 * ID는 매 호출 새로 만들되 테스트가 필요하면 인자로 고정할 수 있다.
 */
object OrderFixtures {

    private val fixtureMonkey: FixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val krw: Currency = Currency.getInstance("KRW")

    fun createOrderCommand(
        storeId: StoreId = StoreId(UUID.randomUUID()),
        terminalId: TerminalId = TerminalId(UUID.randomUUID()),
        clientOrderId: ClientOrderId = ClientOrderId(UUID.randomUUID()),
        currency: Currency = krw,
        lines: List<CreateOrderLine> = listOf(
            CreateOrderLine("SKU-1", "아메리카노", Money.of("1000", krw), 2),
            CreateOrderLine("SKU-2", "카페라떼", Money.of("500", krw), 3),
        ),
    ): CreateOrderCommand =
        fixtureMonkey.giveMeKotlinBuilder<CreateOrderCommand>()
            .setExp(CreateOrderCommand::storeId, storeId)
            .setExp(CreateOrderCommand::terminalId, terminalId)
            .setExp(CreateOrderCommand::clientOrderId, clientOrderId)
            .setExp(CreateOrderCommand::currency, currency)
            .setExp(CreateOrderCommand::lines, lines)
            .sample()
}
