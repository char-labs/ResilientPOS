package com.resilientpos.order.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.checkoutRowCount
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.order.internal.domain.restored
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * 행 잠금 없이 `@Version`에만 기대는 쓰기가 커밋 시점에 버전 충돌을 만나면 낙관적 잠금 예외로 끝나는지 검사한다. Docker가 필요하다.
 * `BaseEntity`에 실행 시점 DB generated 값(`SourceType.DB` 등)이 돌아오면 Hibernate가 갱신 행 수 검사를 건너뛰어 이 테스트가 `JpaSystemException`으로 실패한다.
 * `requestCheckout`의 행 잠금을 지키는 테스트는 아니다. 그쪽은 `OrderRequestCheckoutIntegrationTest`가 본다.
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
        var savedBeforeCommit = false
        try {
            assertFailsWith<OptimisticLockingFailureException> {
                transaction.execute {
                    val stale = assertNotNull(repository.findById(orderId))
                    executor.submit { facade.requestCheckout(OrderCommand.RequestCheckout(orderId, OrderFixtures.storeId)) }.get(10, TimeUnit.SECONDS)

                    // checkout 행 없이 루트 status만 바꾼다. INSERT가 있으면 Hibernate가 INSERT를 먼저 flush해서 unique 위반이 먼저 걸린다.
                    repository.save(
                        stale.restored(status = OrderStatus.CHECKOUT_REQUESTED, checkouts = emptyList()),
                    )
                    savedBeforeCommit = true
                }
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertTrue(savedBeforeCommit, "save는 flush하지 않는다. 충돌은 커밋의 UPDATE에서 드러나야 한다")
        val row = jdbcTemplate.queryForMap("select version, status from t_order where id = ?", orderId.value)
        assertEquals(1L, row["version"], "먼저 커밋한 requestCheckout의 갱신만 남는다")
        assertEquals("CHECKOUT_REQUESTED", row["status"])
        assertEquals(
            1L,
            jdbcTemplate.checkoutRowCount(orderId.value),
        )
    }
}
