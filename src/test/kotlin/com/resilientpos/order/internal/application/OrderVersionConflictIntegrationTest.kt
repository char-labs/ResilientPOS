package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.RequestCheckoutCommand
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * 행 잠금 없이 `@Version`에만 기대는 쓰기가 커밋 시점에 버전 충돌을 만나면 낙관적 잠금 예외로 끝나는지 검사한다. Docker가 필요하다.
 */
class OrderVersionConflictIntegrationTest(
    @Autowired private val facade: OrderCommandFacade,
    @Autowired private val repository: OrderRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transaction: TransactionTemplate,
) : PostgresIntegrationTest() {

    @Test
    fun `읽은 뒤 다른 트랜잭션이 먼저 커밋한 주문을 잠금 없이 저장하면 커밋에서 낙관적 잠금 예외가 난다`() {
        val orderId = facade.create(OrderFixtures.createOrderCommand()).orderId
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFailsWith<OptimisticLockingFailureException> {
                transaction.execute {
                    val stale = assertNotNull(repository.findById(orderId))
                    executor.submit { facade.requestCheckout(RequestCheckoutCommand(orderId)) }.get(10, TimeUnit.SECONDS)

                    // checkout 행 없이 루트 status만 바꾼다. INSERT가 있으면 Hibernate가 INSERT를 먼저 flush해서 unique 위반이 먼저 걸린다.
                    repository.save(
                        Order.restore(
                            id = stale.id,
                            storeId = stale.storeId,
                            terminalId = stale.terminalId,
                            clientOrderId = stale.clientOrderId,
                            currency = stale.currency,
                            status = OrderStatus.CHECKOUT_REQUESTED,
                            lines = stale.lines,
                            checkouts = emptyList(),
                        ),
                    )
                }
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertEquals(
            1L,
            jdbcTemplate.queryForObject("select version from t_order where id = ?", Long::class.java, orderId.value),
            "먼저 커밋한 requestCheckout의 갱신만 남는다",
        )
    }
}
