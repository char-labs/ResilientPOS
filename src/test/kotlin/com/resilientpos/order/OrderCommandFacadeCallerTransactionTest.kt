package com.resilientpos.order

import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.order.internal.application.OrderEventPublisher
import com.resilientpos.order.internal.domain.OrderRepository
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.context.ApplicationEventPublisher

/**
 * 호출자 트랜잭션에 합류하는 생성 경로가 트랜잭션 밖에서 불리면 **저장소를 건드리기 전에** 거절하는지 본다.
 * 거절하지 않으면 주문 행만 자기 트랜잭션으로 커밋되고 호출자가 묶으려던 한 커밋이 조용히 둘로 갈라진다.
 */
class OrderCommandFacadeCallerTransactionTest {

    private val repository = mock(OrderRepository::class.java)
    private val eventPublisher = mock(ApplicationEventPublisher::class.java)
    private val facade = OrderCommandFacade(repository, UuidV7IdentifierGenerator(), Clock.systemUTC(), OrderEventPublisher(eventPublisher))

    @Test
    fun `트랜잭션 밖에서 부르면 저장소를 건드리기 전에 거절한다`() {
        assertFailsWith<IllegalStateException> { facade.createInCallerTransaction(OrderFixtures.createOrderCommand()) }

        verifyNoInteractions(repository)
        verifyNoInteractions(eventPublisher)
    }
}
