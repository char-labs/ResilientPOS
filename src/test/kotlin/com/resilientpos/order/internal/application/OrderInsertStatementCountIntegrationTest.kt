package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderLineId
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.order.internal.domain.OrderRepository
import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

/**
 * 새 행이 추가 SELECT 없이 INSERT 한 번으로 들어가는지 Hibernate 통계의 JDBC 문장 수로 검사한다. Docker가 필요하다.
 * 도메인 ID를 저장 전에 만들기 때문에 Spring Data의 `save`는 `@Version`이 없는 새 엔티티를 기존 행으로 보고 `merge`한다.
 * 그러면 새 행마다 SELECT가 하나 더 나가고 이 테스트의 문장 수가 그만큼 늘어난다.
 */
class OrderInsertStatementCountIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val repository: OrderRepository,
    @Autowired private val transaction: TransactionTemplate,
    @Autowired private val identifierGenerator: IdentifierGenerator,
    @Autowired private val clock: Clock,
    @Autowired entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val statistics: Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @BeforeTest
    fun enableStatistics() {
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun disableStatistics() {
        statistics.isStatisticsEnabled = false
    }

    /**
     * 한 트랜잭션에서 [prepare]로 저장할 주문을 만든 뒤 `save`부터 커밋까지 나간 JDBC 문장 수.
     * INSERT와 UPDATE는 커밋의 flush에서 나가므로 커밋까지 센다. [prepare]의 조회는 세지 않는다.
     */
    private fun statementsOfSave(prepare: () -> Order): Long {
        var beforeSave = 0L
        transaction.executeWithoutResult {
            val order = prepare()
            beforeSave = statistics.prepareStatementCount
            repository.save(order)
        }
        return statistics.prepareStatementCount - beforeSave
    }

    @Test
    fun `새 주문 저장은 주문 조회 한 번과 주문과 line의 INSERT뿐이다`() {
        val order = Order.create(OrderFixtures.createOrderCommand(), identifierGenerator, clock.instant()).aggregate

        val statements = statementsOfSave { order }

        val orderSelect = 1L
        val orderInsert = 1L
        val lineInserts = order.lines.size.toLong()
        assertEquals(orderSelect + orderInsert + lineInserts, statements, "새 line마다 SELECT가 따라붙으면 merge로 빠진 것이다")
    }

    @Test
    fun `기존 주문에 line을 바꾸고 더해 저장하면 새 line은 SELECT 없이 INSERT 한 번이다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val added = OrderLine(OrderLineId(UUID.randomUUID()), "SKU-3", "바닐라라떼", Money.of("700", OrderFixtures.krw), 1)

        val statements = statementsOfSave {
            val stored = assertNotNull(repository.findById(orderId))
            val lines = listOf(stored.lines[0].copy(quantity = 5), stored.lines[1], added)
            Order.restore(
                stored.id, stored.storeId, stored.terminalId, stored.clientOrderId, stored.currency, stored.status, lines, stored.checkouts,
            )
        }

        // 주문은 같은 트랜잭션에서 이미 읽은 관리 엔티티라 다시 조회하지 않고, 값이 같아서 UPDATE도 없다.
        val lineSelect = 1L
        val checkoutSelect = 1L
        val lineInsert = 1L
        val lineUpdate = 1L
        assertEquals(lineSelect + checkoutSelect + lineInsert + lineUpdate, statements, "새 line에 SELECT가 따라붙으면 merge로 빠진 것이다")
        assertEquals(3, assertNotNull(repository.findById(orderId)).lines.size)
    }

    @Test
    fun `checkout을 더한 주문을 저장하면 새 checkout은 SELECT 없이 INSERT 한 번이다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId

        val statements = statementsOfSave {
            assertNotNull(repository.findByIdForUpdate(orderId)).requestCheckout(identifierGenerator, clock.instant()).aggregate
        }

        val lineSelect = 1L
        val checkoutSelect = 1L
        val checkoutInsert = 1L
        val orderUpdate = 1L
        assertEquals(lineSelect + checkoutSelect + checkoutInsert + orderUpdate, statements, "새 checkout에 SELECT가 따라붙으면 merge로 빠진 것이다")
        assertEquals(1, assertNotNull(repository.findById(orderId)).checkouts.size)
    }
}
