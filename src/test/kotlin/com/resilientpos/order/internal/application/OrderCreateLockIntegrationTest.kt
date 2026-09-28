package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.platform.Lock
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 같은 `(terminalId, clientOrderId)`의 생성이 겹치면 늦은 요청이 락에서 앞 요청의 커밋을 기다렸다가 그 주문을 받는지 본다.
 *
 * 앞 요청을 락 블록으로 흉내 낸다. 블록 안에서 주문을 넣고, 그동안 늦은 요청을 다른 스레드에서 부른다.
 * 락이 없으면 늦은 요청은 주문을 찾지 못하고 넣다가 unique에 걸려 실패한다.
 */
class OrderCreateLockIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val repository: OrderRepository,
    @Autowired private val identifierGenerator: IdentifierGenerator,
    @Autowired private val clock: Clock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    @Test
    fun `같은 키의 생성이 겹치면 늦은 요청이 앞 주문을 받는다`() {
        val command = OrderFixtures.createOrderCommand()
        val executor = Executors.newSingleThreadExecutor()

        try {
            lateinit var late: Future<OrderCommand.Create.Result>
            val first = Lock.executeWithLock(OrderCommandFacade.createLockKey(command.terminalId, command.clientOrderId)) {
                val saved = repository.save(Order.create(command, identifierGenerator, clock.instant()).aggregate)
                late = executor.submit<OrderCommand.Create.Result> { facade.create(command) }
                runCatching { late.get(500, TimeUnit.MILLISECONDS) }
                assertFalse(late.isDone, "늦은 요청이 락을 기다리지 않았다")
                saved
            }

            assertEquals(first.id, late.get(5, TimeUnit.SECONDS).orderId)
            assertEquals(
                1L,
                jdbcTemplate.queryForObject<Long>(
                    "select count(*) from t_order where terminal_id = ? and client_order_id = ?",
                    command.terminalId.value,
                    command.clientOrderId.value,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
