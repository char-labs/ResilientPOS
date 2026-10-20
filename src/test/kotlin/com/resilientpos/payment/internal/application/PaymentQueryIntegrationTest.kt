package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.inTransaction
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentAttemptView
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentQuery
import com.resilientpos.payment.PaymentQueryFacade
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionOperations

/**
 * 결제 시도 조회를 실제 PostgreSQL에서 본다. 시도는 저장소로 직접 넣는다. 이벤트를 내지 않아서 승인 리스너가 상태를 바꾸지 않는다.
 * 테스트마다 다른 `orderId`를 쓰고 매장도 따로 만들어 서로 섞이지 않는다.
 */
class PaymentQueryIntegrationTest(
    @Autowired private val queryFacade: PaymentQueryFacade,
    @Autowired private val repository: PaymentAttemptRepository,
    @Autowired private val transactions: TransactionOperations,
) : PostgresIntegrationTest() {

    private val krw: Currency = Currency.getInstance("KRW")
    private val ids = UuidV7IdentifierGenerator()

    private fun attempt(
        orderId: UUID,
        storeId: StoreId,
        checkoutNo: Int = 1,
        status: PaymentAttemptStatus = PaymentAttemptStatus.REQUESTED,
        amount: String = "4500",
        kind: PaymentAttemptKind = PaymentAttemptKind.AUTHORIZATION,
        retriedFrom: PaymentId? = null,
    ) = PaymentAttempt.restore(
        id = PaymentId(ids.next()),
        orderId = orderId,
        storeId = storeId,
        terminalId = TerminalId(UUID.randomUUID()),
        checkoutNo = checkoutNo,
        kind = kind,
        amount = Money.of(amount, krw),
        status = status,
        providerReference = null,
        retriedFrom = retriedFrom,
        unknownReason = null,
    ).also { saved -> transactions.inTransaction { repository.save(saved) } }

    private fun PaymentAttempt.view() = PaymentAttemptView(id, checkoutNo, kind, status, amount)

    private fun find(orderId: UUID, storeId: StoreId) = queryFacade.findAttempts(PaymentQuery.FindAttempts(orderId, storeId))

    @Test
    fun `한 주문의 시도를 회차와 종류를 가리지 않고 생성 순으로 각 필드와 함께 돌려준다`() {
        val storeId = StoreId(UUID.randomUUID())
        val orderId = UUID.randomUUID()
        val declined = attempt(orderId, storeId, 1, PaymentAttemptStatus.DECLINED, "4501")
        val retried = attempt(orderId, storeId, 1, PaymentAttemptStatus.APPROVED, "4501", retriedFrom = declined.id)
        val secondRound = attempt(orderId, storeId, 2, PaymentAttemptStatus.UNKNOWN, "1200")
        val cancellation = attempt(orderId, storeId, 2, PaymentAttemptStatus.REQUESTED, "1200", PaymentAttemptKind.CANCELLATION)

        val views = find(orderId, storeId)

        assertEquals(listOf(declined, retried, secondRound, cancellation).map { it.view() }, views)
        assertEquals(listOf(1, 1, 2, 2), views.map { it.checkoutNo })
        assertEquals(Money.of("4501", krw), views.first().amount)
    }

    @Test
    fun `다른 매장의 storeId로 조회하면 같은 주문의 시도가 있어도 빈 목록이다`() {
        val orderId = UUID.randomUUID()
        attempt(orderId, StoreId(UUID.randomUUID()))

        assertTrue(find(orderId, StoreId(UUID.randomUUID())).isEmpty())
    }

    @Test
    fun `시도가 없는 주문은 빈 목록이다`() {
        assertTrue(find(UUID.randomUUID(), StoreId(UUID.randomUUID())).isEmpty())
    }

    @Test
    fun `다른 주문의 시도는 섞이지 않는다`() {
        val storeId = StoreId(UUID.randomUUID())
        val orderId = UUID.randomUUID()
        val mine = attempt(orderId, storeId)
        attempt(UUID.randomUUID(), storeId)

        assertEquals(listOf(mine.view()), find(orderId, storeId))
    }
}
