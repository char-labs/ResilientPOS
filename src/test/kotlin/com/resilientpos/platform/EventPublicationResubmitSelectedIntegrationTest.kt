package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import io.micrometer.core.instrument.MeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 재전송 한 주기가 읽은 `FAILED` 행 수를 `event.publication.resubmit.selected`로 세는지 본다.
 * 재전송 작업의 다른 동작은 `EventPublicationResubmitterIntegrationTest`가 본다.
 * 작업은 다른 테스트가 남긴 `FAILED` 행도 읽으므로 기대값은 호출 직전의 `FAILED` 행 수다.
 */
class EventPublicationResubmitSelectedIntegrationTest(
    private val resubmitter: EventPublicationResubmitter,
    private val orderFacade: OrderCommandFacade,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : PostgresIntegrationTest() {

    /** 4500원은 끝자리가 0이라 FakePaymentGateway가 승인한다. fulfillment까지 끝나면 `order.paid` 발행 기록도 완료돼 있다. */
    private fun failedOrderPaidPublication() {
        val created = orderFacade.create(
            OrderFixtures.createOrderCommand(
                lines = listOf(OrderCommand.Create.Line("SKU-1", "아메리카노", Money.of("4500", OrderFixtures.krw), 1)),
            ),
        )
        orderFacade.requestCheckout(OrderFixtures.requestCheckoutCommand(created.orderId, OrderFixtures.storeId, OrderFixtures.terminalId))
        val orderId = created.orderId.value
        awaitUntil("order.paid 발행 기록이 완료된다") {
            jdbcTemplate.queryForObject<Long>(
                "select count(*) from event_publication where event_type like ? and serialized_event like ? and completion_date is not null",
                "%.OrderPaid", "%$orderId%",
            ) == 1L
        }
        jdbcTemplate.update(
            "update event_publication set status = 'FAILED', completion_date = null where event_type like ? and serialized_event like ?",
            "%.OrderPaid", "%$orderId%",
        )
    }

    /** Modulith가 재전송 대상으로 읽는 행이다. 상태가 비어 있는 미완료 행도 실패로 본다. */
    private fun failedRows(): Long = checkNotNull(
        jdbcTemplate.queryForObject<Long>(
            "select count(*) from event_publication where status = 'FAILED' or (status is null and completion_date is null)",
        ),
    )

    private fun selected(): Double = meterRegistry.find("event.publication.resubmit.selected").counter()?.count() ?: 0.0

    @Test
    fun `재전송 한 주기가 읽은 FAILED 행 수만큼 선택 건수가 오른다`() {
        failedOrderPaidPublication()
        resubmitter.markStalePublicationsFailed()
        val expected = failedRows()
        val before = selected()

        resubmitter.resubmitAndPurge()

        assertTrue(expected >= 1, "이 테스트가 만든 FAILED 행이 있어야 한다")
        assertEquals(expected.toDouble(), selected() - before)
    }
}
