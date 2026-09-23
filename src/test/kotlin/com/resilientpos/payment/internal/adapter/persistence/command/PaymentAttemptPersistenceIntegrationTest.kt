package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.Money
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

/**
 * 한 주문에 결제 시도가 여러 건 달리는지, 그러면서도 같은 checkout의 승인이 중복되지 않는지 실제 PostgreSQL에서 확인한다.
 *
 * `testing-strategy.md`는 Repository 전용 테스트를 TDD 대상에서 빼지만, 이 검사들은 스키마 결정 자체를 지킨다.
 * Facade 통합 테스트가 생긴 뒤에도 옮기지 않았다 — 그쪽은 이벤트 경로를 보고, 여기는 통화·금액 scale·종류처럼
 * 한 행이 온전히 오가는지를 본다. 둘의 목적이 다르다.
 *
 * **`@Transactional`을 붙이지 않는다.** 붙이면 저장 직후의 조회가 같은 영속성 컨텍스트의 관리 엔티티를 돌려줘
 * DB를 한 번도 읽지 않고 통과한다. 통화 왕복도 금액 scale도 검증되지 않는다.
 * 격리는 테스트마다 다른 `orderId`를 쓰는 것으로 한다. 행은 JVM 수명 동안 남으므로 전체 개수를 세는 단언을 두지 않는다.
 */
class PaymentAttemptPersistenceIntegrationTest(
    @Autowired private val repository: PaymentAttemptJpaRepository,
) : PostgresIntegrationTest() {

    private val krw: Currency = Currency.getInstance("KRW")
    private val ids = UuidV7IdentifierGenerator()
    private val now: Instant = Instant.parse("2026-09-22T01:00:00Z")

    private fun authorization(orderId: UUID, amount: String = "4500", checkoutNo: Int = 1) =
        PaymentAttempt.create(
            PaymentCommand.CreateAttempt(
                orderId = orderId,
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = checkoutNo,
                amount = Money.of(amount, krw),
            ),
            ids,
            now,
        ).aggregate

    private fun stored(
        orderId: UUID,
        checkoutNo: Int = 1,
        kind: PaymentAttemptKind = PaymentAttemptKind.AUTHORIZATION,
        status: PaymentAttemptStatus = PaymentAttemptStatus.REQUESTED,
        amount: Money = Money.of("4500", krw),
        providerReference: String? = null,
    ) = PaymentAttempt.restore(
        id = PaymentId(ids.next()),
        orderId = orderId,
        storeId = StoreId(UUID.randomUUID()),
        terminalId = TerminalId(UUID.randomUUID()),
        checkoutNo = checkoutNo,
        kind = kind,
        amount = amount,
        status = status,
        providerReference = providerReference,
    )

    private fun save(vararg attempts: PaymentAttempt) =
        attempts.forEach { repository.save(PaymentAttemptEntity.from(it)) }

    @Test
    fun `거절 뒤 재시도와 취소가 같은 주문에 나란히 쌓인다`() {
        // 1-to-many가 성립하는 실제 경로다. 진행 중인 승인이 하나뿐이면 회차가 같아도 여러 건이 들어간다.
        val orderId = UUID.randomUUID()

        save(
            stored(orderId, status = PaymentAttemptStatus.DECLINED),
            stored(orderId, status = PaymentAttemptStatus.DECLINED),
            stored(orderId, status = PaymentAttemptStatus.APPROVED),
            stored(orderId, kind = PaymentAttemptKind.CANCELLATION, status = PaymentAttemptStatus.APPROVED),
            stored(orderId, checkoutNo = 2),
        )

        assertEquals(5, repository.findAllByOrderIdOrderById(orderId).size)
    }

    @Test
    fun `같은 checkout에 진행 중인 승인은 하나뿐이다`() {
        // 이벤트가 두 번 전달돼도 두 번째 INSERT가 여기서 막힌다.
        // 이 제약이 없으면 두 행의 값이 완전히 같아서 중복 청구와 정당한 재시도를 사후에 구분할 수 없다.
        val orderId = UUID.randomUUID()
        save(stored(orderId))

        assertFailsWith<DataIntegrityViolationException> { save(stored(orderId)) }
    }

    @Test
    fun `저장한 시도를 도메인으로 그대로 복원한다`() {
        val orderId = UUID.randomUUID()
        val saved = authorization(orderId, amount = "12345")

        save(saved)

        val restored = repository.findAllByOrderIdOrderById(orderId).single().toDomain()
        assertEquals(saved.id, restored.id)
        assertEquals(saved.orderId, restored.orderId)
        assertEquals(saved.storeId, restored.storeId)
        assertEquals(saved.terminalId, restored.terminalId)
        assertEquals(saved.checkoutNo, restored.checkoutNo)
        assertEquals(saved.amount, restored.amount)
        assertEquals(PaymentAttemptKind.AUTHORIZATION, restored.kind)
        assertEquals(PaymentAttemptStatus.REQUESTED, restored.status)
        assertNull(restored.providerReference)
    }

    @Test
    fun `KRW가 아닌 통화와 취소 시도도 값이 그대로 오간다`() {
        // 다른 테스트가 전부 KRW·AUTHORIZATION·providerReference=null이라
        // 매핑이 그 값들을 그대로 박아 넣어도 통과한다. 다른 값으로 한 번 더 확인한다.
        val usd = Currency.getInstance("USD")
        val orderId = UUID.randomUUID()
        val attempt = stored(
            orderId,
            checkoutNo = 3,
            kind = PaymentAttemptKind.CANCELLATION,
            status = PaymentAttemptStatus.UNKNOWN,
            amount = Money.of("10.99", usd),
            providerReference = "fake-order-1",
        )

        save(attempt)

        val restored = repository.findAllByOrderIdOrderById(orderId).single().toDomain()
        assertEquals(PaymentAttemptKind.CANCELLATION, restored.kind)
        assertEquals(PaymentAttemptStatus.UNKNOWN, restored.status)
        assertEquals("fake-order-1", restored.providerReference)
        assertEquals(3, restored.checkoutNo)
        // 통화가 보존되지 않으면 Money 비교가 통화 불일치로 깨진다.
        // 10.99는 컬럼 scale이 0이나 1이면 반올림되어 값이 달라진다.
        assertEquals(Money.of("10.99", usd), restored.amount)
        assertEquals(usd, restored.amount.currency)
    }

    @Test
    fun `0원 시도도 승인 상태 그대로 저장되고 복원된다`() {
        val orderId = UUID.randomUUID()

        save(authorization(orderId, amount = "0"))

        val restored = repository.findAllByOrderIdOrderById(orderId).single().toDomain()
        assertEquals(PaymentAttemptStatus.APPROVED, restored.status)
        assertEquals(Money.of("0", krw), restored.amount)
        assertNull(restored.providerReference)
    }

    @Test
    fun `정수부 15자리 금액이 잘리지 않고 오간다`() {
        // Money.MAX_INTEGER_DIGITS = 15와 numeric(19,4)의 관계를 코드로 고정한다.
        val orderId = UUID.randomUUID()
        val max = Money.of("9".repeat(Money.MAX_INTEGER_DIGITS), krw)

        save(stored(orderId, amount = max))

        assertEquals(max, repository.findAllByOrderIdOrderById(orderId).single().toDomain().amount)
    }
}
