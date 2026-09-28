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
 * `OrderCommand.Create` fixture. 판단에 중요한 통화·line 단가·수량은 고정하고
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
        // 합계 3502원은 끝자리가 2라 FakePaymentGateway가 결과를 미확인으로 남긴다. checkout 뒤 결제 리스너가
        // 비동기로 주문을 PAID로 바꾸지 않게 해서, checkout 직후 상태를 읽는 테스트가 경합에 걸리지 않는다.
        // 끝자리 규칙은 FakePaymentGatewayTest의 `끝자리가 2이면 결과를 모른다`가 고정한다.
        lines: List<OrderCommand.Create.Line> = listOf(
            OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("1001", krw), 2),
            OrderCommand.Create.Line("SKU-2", "카페라떼", Money.of("500", krw), 3),
        ),
    ): OrderCommand.Create =
        fixtureMonkey.giveMeKotlinBuilder<OrderCommand.Create>()
            .setExp(OrderCommand.Create::storeId, storeId)
            .setExp(OrderCommand.Create::terminalId, terminalId)
            .setExp(OrderCommand.Create::clientOrderId, clientOrderId)
            .setExp(OrderCommand.Create::currency, currency)
            .setExp(OrderCommand.Create::lines, lines)
            .sample()

    /** 단가와 수량만 정한 line. 결제 결말은 FakePaymentGateway가 합계의 끝자리로 정한다. */
    fun line(unitPrice: String, quantity: Int = 1): OrderCommand.Create.Line =
        OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of(unitPrice, krw), quantity)
}

/** 주문을 만들고 checkout을 요청한다. 결제 쪽 테스트가 `OrderPaymentRequested`를 내게 하는 가장 짧은 길이다. */
fun OrderCommandFacade.checkout(command: OrderCommand.Create = OrderFixtures.createOrderCommand()): OrderId {
    val created = create(command)
    requestCheckout(OrderCommand.RequestCheckout(created.orderId))
    return created.orderId
}
