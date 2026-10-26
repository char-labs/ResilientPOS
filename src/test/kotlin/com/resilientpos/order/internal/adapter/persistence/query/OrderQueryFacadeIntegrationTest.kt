package com.resilientpos.order.internal.adapter.persistence.query

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderDetailView
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderQueryFacade
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.store.StoreId
import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 주문 단건 조회를 실제 PostgreSQL에서 본다. 주문은 Facade로 만들어 저장된 행을 읽는다. Docker가 필요하다.
 * 통계는 `SessionFactory` 전체의 누적값이라 테스트를 병렬로 돌리지 않는 동안만 문장 수가 이 구간의 것이다(OrderInsertStatementCountIntegrationTest와 같다).
 */
class OrderQueryFacadeIntegrationTest(
    private val commands: OrderCommandFacade,
    private val queryFacade: OrderQueryFacade,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val statistics: Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private var statisticsWereEnabled = false

    @BeforeTest
    fun enableStatistics() {
        statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun restoreStatistics() {
        statistics.isStatisticsEnabled = statisticsWereEnabled
    }

    private fun get(orderId: OrderId, storeId: StoreId = OrderFixtures.storeId): OrderDetailView =
        queryFacade.getOrder(OrderQuery.GetOrder(orderId, storeId))

    private fun storedVersion(orderId: OrderId): Long =
        checkNotNull(jdbc.queryForObject<Long>("select version from t_order where id = ?", orderId.value))

    @Test
    fun `checkout한 주문은 상태와 version과 1회차 금액과 line과 합계와 발생 시각을 돌려준다`() {
        val occurredAt = clock.instant().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
        val command = OrderFixtures.createOrderCommand(occurredAt = occurredAt)
        val orderId = commands.createAndRequestCheckout(command)

        val view = get(orderId)

        val total = Money.of("3502", OrderFixtures.krw)
        assertEquals(orderId, view.orderId)
        assertEquals(command.clientOrderId, view.clientOrderId)
        assertEquals(command.terminalId, view.terminalId)
        assertEquals(OrderStatus.CHECKOUT_REQUESTED, view.status)
        assertEquals(storedVersion(orderId), view.version)
        assertEquals(1L, view.version)
        assertEquals(OrderFixtures.krw, view.currency)
        assertEquals(total, view.total)
        assertEquals(occurredAt, view.occurredAt)
        assertEquals(
            listOf(
                OrderDetailView.Line("SKU-1", "아메리카노", Money.of("1001", OrderFixtures.krw), 2),
                OrderDetailView.Line("SKU-2", "카페라떼", Money.of("500", OrderFixtures.krw), 3),
            ),
            view.lines,
        )
        assertEquals(listOf(OrderDetailView.Checkout(1, total)), view.checkouts)
    }

    @Test
    fun `checkout 전 OPEN 주문은 checkout 없이 정상으로 돌려준다`() {
        val orderId = commands.create(OrderFixtures.createOrderCommand()).orderId

        val view = get(orderId)

        assertEquals(OrderStatus.OPEN, view.status)
        assertEquals(0L, view.version)
        assertEquals(emptyList(), view.checkouts)
        assertEquals(2, view.lines.size)
        assertEquals(Money.of("3502", OrderFixtures.krw), view.total)
    }

    @Test
    fun `다른 매장의 주문과 없는 주문은 같은 ORDER_NOT_FOUND다`() {
        val orderId = commands.create(OrderFixtures.createOrderCommand()).orderId

        val otherStore = assertFailsWith<DomainException> { get(orderId, StoreId(UUID.randomUUID())) }
        val missing = assertFailsWith<DomainException> { get(OrderId(UUID.randomUUID())) }

        listOf(otherStore, missing).forEach {
            assertEquals(Order.ORDER_NOT_FOUND, it.code)
            assertEquals(DomainErrorKind.NOT_FOUND, it.kind)
        }
        assertEquals(otherStore.message, missing.message)
    }

    @Test
    fun `단건 조회는 주문과 checkout 한 쿼리와 line 한 쿼리로 두 문장이다`() {
        // checkout 없는 주문이라 비동기 처리(결제 시도 생성)가 없어 전역 통계에 다른 문장이 섞이지 않는다.
        // checkout을 따로 읽는 구현이면 행이 없어도 문장이 3개가 된다.
        val orderId = commands.create(OrderFixtures.createOrderCommand()).orderId
        val before = statistics.prepareStatementCount
        get(orderId)

        assertEquals(2L, statistics.prepareStatementCount - before, "주문 행과 checkout 한 쿼리, line 한 쿼리")
    }
}
