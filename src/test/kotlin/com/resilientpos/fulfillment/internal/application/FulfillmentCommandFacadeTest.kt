package com.resilientpos.fulfillment.internal.application

import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.fulfillment.internal.domain.Fulfillment
import com.resilientpos.fulfillment.internal.domain.FulfillmentRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Facade가 원본 주문당 fulfillment를 한 번만 만드는지 고정한다.
 * 저장소는 가짜를 쓴다. DB 없이 값 비교로 끝나는 검사다. 실제 unique 제약이 닫는 창은
 * `FulfillmentUniqueOrderIntegrationTest`가 실제 PostgreSQL로 확인한다.
 */
class FulfillmentCommandFacadeTest {

    private class RecordingRepository : FulfillmentRepository {
        val saved = mutableListOf<Fulfillment>()
        private val byOrderId = mutableMapOf<UUID, Fulfillment>()

        override fun findByOrderId(orderId: UUID) = byOrderId[orderId]
        override fun save(fulfillment: Fulfillment) = fulfillment.also {
            saved += it
            byOrderId[it.orderId] = it
        }
    }

    private fun facade(repository: FulfillmentRepository) =
        FulfillmentCommandFacade(repository, UuidV7IdentifierGenerator())

    @Test
    fun `같은 주문의 요청이 다시 와도 fulfillment는 하나이고 처음 fulfillment를 돌려준다`() {
        val repository = RecordingRepository()
        val facade = facade(repository)
        val orderId = UUID.randomUUID()

        val first = facade.createFulfillment(FulfillmentCommand.Create(orderId))
        val second = facade.createFulfillment(FulfillmentCommand.Create(orderId))

        assertEquals(first.fulfillmentId, second.fulfillmentId)
        assertEquals(true to false, first.created to second.created)
        assertEquals(1, repository.saved.size)
    }
}
