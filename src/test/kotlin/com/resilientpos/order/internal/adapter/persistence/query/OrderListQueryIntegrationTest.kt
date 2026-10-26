package com.resilientpos.order.internal.adapter.persistence.query

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderQueryFacade
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.store.StoreId
import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics

/**
 * 점주 주문 목록 조회를 실제 PostgreSQL에서 본다. 매장마다 새 id를 써서 다른 테스트의 주문과 섞이지 않는다.
 * 같은 시각 동률의 기대 순서는 id 문자열의 사전순이다. 소문자 hex 문자열 순서는 PostgreSQL `uuid`의 부호 없는 바이트 순서와 같다.
 * `UUID.compareTo`는 상위 64비트를 부호 있는 수로 비교해 다를 수 있고, UUID v7은 같은 밀리초 안에서 생성 순서와 같지 않다.
 */
class OrderListQueryIntegrationTest(
    private val commands: OrderCommandFacade,
    private val queryFacade: OrderQueryFacade,
    private val clock: Clock,
    entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val statistics: Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private var statisticsWereEnabled = false

    private val base: Instant = clock.instant().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun enableStatistics() {
        statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun restoreStatistics() {
        statistics.isStatisticsEnabled = statisticsWereEnabled
    }

    private fun place(storeId: StoreId, occurredAt: Instant, lines: List<OrderCommand.Create.Line>? = null): OrderId {
        val command = if (lines == null) {
            OrderFixtures.createOrderCommand(storeId = storeId, occurredAt = occurredAt)
        } else {
            OrderFixtures.createOrderCommand(storeId = storeId, occurredAt = occurredAt, lines = lines)
        }
        return commands.create(command).orderId
    }

    private fun find(
        storeId: StoreId,
        from: Instant? = null,
        to: Instant? = null,
        status: OrderStatus? = null,
        size: Int = 20,
        after: OrderQuery.FindOrders.Cursor? = null,
    ): OrderQuery.FindOrders.Result =
        queryFacade.findOrders(OrderQuery.FindOrders(storeId, OrderQuery.FindOrders.Criteria(from, to, status, size, after)))

    @Test
    fun `발생 시각 최신순에 같은 시각은 id 내림차순으로 cursor를 따라 끝까지 한 번씩 넘긴다`() {
        val store = StoreId(UUID.randomUUID())
        val oldest = place(store, base)
        val ties = List(3) { place(store, base.plusSeconds(60)) }.sortedByDescending { it.value.toString() }
        val newest = place(store, base.plusSeconds(120))
        val expected = listOf(newest) + ties + oldest

        val pages = mutableListOf<OrderQuery.FindOrders.Result>()
        var cursor: OrderQuery.FindOrders.Cursor? = null
        do {
            val page = find(store, size = 2, after = cursor)
            pages += page
            check(pages.size <= 5) { "cursor가 앞으로 나아가지 않는다" }
            cursor = page.next
        } while (cursor != null)

        assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
        assertEquals(expected, pages.flatMap { page -> page.items.map { it.orderId } })
        assertNull(pages.last().next)
        assertEquals(OrderQuery.FindOrders.Cursor(base.plusSeconds(60), ties[0]), pages[0].next)
    }

    @Test
    fun `기간은 from 이상 to 미만이다`() {
        val store = StoreId(UUID.randomUUID())
        place(store, base)
        val at1 = place(store, base.plusSeconds(3600))
        val at2 = place(store, base.plusSeconds(7200))
        place(store, base.plusSeconds(10800))

        val result = find(store, from = base.plusSeconds(3600), to = base.plusSeconds(10800))

        assertEquals(listOf(at2, at1), result.items.map { it.orderId })
        assertEquals(emptyList(), find(store, from = base.plusSeconds(7200), to = base.plusSeconds(7200)).items)
    }

    @Test
    fun `status는 그 상태의 주문만 거른다`() {
        val store = StoreId(UUID.randomUUID())
        val open = place(store, base)
        val requested = commands.createAndRequestCheckout(
            OrderFixtures.createOrderCommand(storeId = store, occurredAt = base.plusSeconds(60)),
        )

        assertEquals(listOf(requested), find(store, status = OrderStatus.CHECKOUT_REQUESTED).items.map { it.orderId })
        assertEquals(listOf(open), find(store, status = OrderStatus.OPEN).items.map { it.orderId })
        assertEquals(emptyList(), find(store, status = OrderStatus.PAID).items)
    }

    @Test
    fun `항목은 요약 필드와 line 단가 곱하기 수량의 합계를 담는다`() {
        val store = StoreId(UUID.randomUUID())
        val withLines = place(store, base.plusSeconds(60))
        val single = place(store, base, listOf(OrderFixtures.line("700", quantity = 4)))

        val items = find(store).items

        assertEquals(listOf(withLines, single), items.map { it.orderId })
        assertEquals(Money.of("3502", OrderFixtures.krw), items[0].total)
        assertEquals(Money.of("2800", OrderFixtures.krw), items[1].total)
        assertEquals(OrderStatus.OPEN, items[0].status)
        assertEquals(OrderFixtures.krw, items[0].currency)
        assertEquals(base.plusSeconds(60), items[0].occurredAt)
    }

    @Test
    fun `다른 매장의 주문은 섞이지 않는다`() {
        val mine = StoreId(UUID.randomUUID())
        val other = StoreId(UUID.randomUUID())
        val own = place(mine, base)
        place(other, base.plusSeconds(60))

        assertEquals(listOf(own), find(mine).items.map { it.orderId })
    }

    @Test
    fun `목록은 합계를 포함해 한 문장으로 읽는다`() {
        // 비동기 처리가 없는 OPEN 주문만 만들어 전역 통계에 다른 문장이 섞이지 않는다.
        val store = StoreId(UUID.randomUUID())
        repeat(3) { place(store, base.plusSeconds(it * 60L)) }
        val before = statistics.prepareStatementCount

        find(store, size = 2)

        assertEquals(1L, statistics.prepareStatementCount - before)
    }
}
