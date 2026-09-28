package com.resilientpos.order

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.order.internal.application.OrderEventPublisher
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.context.ApplicationEventPublisher

/**
 * line 개수 상한이 **저장소를 건드리기 전에** 걸리는지 본다.
 * `Order.create`까지 미루면 조회 문장이 먼저 나가면서 커넥션을 잡는다. 요청 하나가 쓰는 자원을
 * 줄이려는 상한이 자원을 쓴 뒤에 걸리면 목적을 절반만 달성한다.
 */
class OrderCommandFacadeLineLimitTest {

    private val repository = mock(OrderRepository::class.java)
    private val eventPublisher = mock(ApplicationEventPublisher::class.java)
    private val facade = OrderCommandFacade(repository, UuidV7IdentifierGenerator(), Clock.systemUTC(), OrderEventPublisher(eventPublisher))

    @Test
    fun `line 개수가 상한을 넘으면 저장소를 부르기 전에 거절한다`() {
        val failure = assertFailsWith<DomainException> { facade.create(command(Order.MAX_LINES + 1)) }

        assertEquals(Order.TOO_MANY_ORDER_LINES, failure.code)
        verifyNoInteractions(repository)
        verifyNoInteractions(eventPublisher)
    }

    private fun command(lineCount: Int): OrderCommand.Create {
        val krw = Currency.getInstance("KRW")
        return OrderCommand.Create(
            storeId = StoreId(UUID.randomUUID()),
            terminalId = TerminalId(UUID.randomUUID()),
            clientOrderId = ClientOrderId(UUID.randomUUID()),
            currency = krw,
            lines = List(lineCount) { OrderCommand.Create.Line("SKU-$it", "상품 $it", Money.of("1000", krw), 1) },
        )
    }
}
