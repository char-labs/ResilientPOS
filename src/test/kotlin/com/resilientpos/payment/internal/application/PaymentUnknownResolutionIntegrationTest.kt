package com.resilientpos.payment.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.domain.AuthorizationOutcome
import com.resilientpos.payment.internal.domain.AuthorizationRequest
import com.resilientpos.payment.internal.domain.PaymentGateway
import com.resilientpos.platform.Tx
import jakarta.persistence.EntityManagerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 결과를 모른 채 남은 시도를 provider 조회로 확정하는 `resolveUnknown`을 실제 DB·발행 기록과 함께 본다.
 * 시도는 [FakePaymentGateway]의 끝자리(2·3·4)로 `UNKNOWN`까지 태워 만든다. 테스트마다 금액이 달라야 한다.
 */
class PaymentUnknownResolutionIntegrationTest(
    @Autowired private val orderFacade: OrderCommandFacade,
    @Autowired private val paymentManagement: PaymentManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val gateway: ProbingGateway,
    @Autowired private val declinedEvents: DeclinedRecorder,
) : PostgresIntegrationTest() {

    /** 거절 사실은 소비자가 없어 발행 기록에 남지 않는다. 같은 프로세스에서 발행된 것을 직접 받아 적는다. */
    class DeclinedRecorder {
        private val received = CopyOnWriteArrayList<PaymentEvent.Declined>()

        @EventListener
        fun record(event: PaymentEvent.Declined) {
            received += event
        }

        fun countFor(paymentId: PaymentId) = received.count { it.paymentId == paymentId }
    }

    /** [FakePaymentGateway]에 위임하면서 `query`의 시도별 호출 횟수와, 호출 순간 이 스레드에 DB 자원이 묶여 있었는지를 적는다. */
    class ProbingGateway(private val emf: EntityManagerFactory, private val dataSource: DataSource) : PaymentGateway {
        private val delegate = FakePaymentGateway()
        private val queryCalls = ConcurrentHashMap<PaymentId, AtomicInteger>()
        private val resourceBound = ConcurrentHashMap<PaymentId, Boolean>()

        override fun authorize(request: AuthorizationRequest): AuthorizationOutcome = delegate.authorize(request)

        override fun query(request: AuthorizationRequest): AuthorizationOutcome {
            queryCalls.computeIfAbsent(request.paymentId) { AtomicInteger() }.incrementAndGet()
            resourceBound[request.paymentId] =
                TransactionSynchronizationManager.hasResource(emf) || TransactionSynchronizationManager.hasResource(dataSource)
            return delegate.query(request)
        }

        fun queryCallsFor(paymentId: PaymentId) = queryCalls[paymentId]?.get() ?: 0

        fun resourceBoundDuringQuery(paymentId: PaymentId) = resourceBound[paymentId]
    }

    @TestConfiguration
    class ProbingGatewayConfiguration {
        @Bean
        @Primary
        fun probingGateway(emf: EntityManagerFactory, dataSource: DataSource) = ProbingGateway(emf, dataSource)

        @Bean
        fun declinedRecorder() = DeclinedRecorder()
    }

    private fun attempt(orderId: UUID): Map<String, Any?>? = jdbcTemplate.queryForList(
        "select id, status, provider_reference from t_payment_attempt where order_id = ?",
        orderId,
    ).singleOrNull()

    /** 주문을 checkout해 시도가 `UNKNOWN`이 될 때까지 기다리고 그 시도를 돌려준다. */
    private fun unknownAttempt(unitPrice: String): PaymentId {
        val orderId = orderFacade.createAndRequestCheckout(
            OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice))),
        ).value
        awaitUntil("시도가 UNKNOWN이 된다", { ". 현재: ${attempt(orderId)}" }) {
            attempt(orderId)?.get("status") == PaymentAttemptStatus.UNKNOWN.name
        }
        return PaymentId(requireNotNull(attempt(orderId))["id"] as UUID)
    }

    private fun row(paymentId: PaymentId): Map<String, Any?> = jdbcTemplate.queryForList(
        "select status, provider_reference, query_count, unknown_reason, reconciliation_requested_at from t_payment_attempt where id = ?",
        paymentId.value,
    ).single()

    /** 그 시도의 사실 중 `eventSuffix`로 끝나는 타입의 발행 기록 수. 직렬화한 이벤트에 `paymentId`가 들어 있다. */
    private fun publications(paymentId: PaymentId, eventSuffix: String): Int = requireNotNull(jdbcTemplate.queryForObject<Int>(
        "select count(*) from event_publication where event_type like ? and serialized_event like ?",
        "%$eventSuffix",
        "%${paymentId.value}%",
    ))

    private fun completedPublications(paymentId: PaymentId): Int = requireNotNull(jdbcTemplate.queryForObject<Int>(
        "select count(*) from event_publication where event_type like '%PaymentReconciliationRequested' and serialized_event like ? and completion_date is not null",
        "%${paymentId.value}%",
    ))

    /** 그 시도의 넘김 사실을 직렬화한 발행 기록. */
    private fun serializedFact(paymentId: PaymentId): String = requireNotNull(jdbcTemplate.queryForObject<String>(
        "select serialized_event from event_publication where event_type like '%PaymentReconciliationRequested' and serialized_event like ?",
        "%${paymentId.value}%",
    ))

    @Test
    fun `조회가 승인이면 시도가 APPROVED가 되고 provider 참조가 남고 승인 발행 기록이 생긴다`() {
        val paymentId = unknownAttempt("4502")
        assertEquals(0, publications(paymentId, "PaymentApproved"))

        val result = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(PaymentCommand.ResolveUnknown.Result(paymentId, PaymentAttemptStatus.APPROVED), result)
        val row = row(paymentId)
        assertEquals(PaymentAttemptStatus.APPROVED.name, row["status"])
        assertTrue((assertNotNull(row["provider_reference"]) as String).startsWith("FAKE-"))
        // 발행 기록이 확정과 같은 커밋에 들어갔으므로 반환 직후 이미 있다.
        assertEquals(1, publications(paymentId, "PaymentApproved"))
    }

    @Test
    fun `조회가 거절이면 시도가 DECLINED가 되고 거절 사실이 발행된다`() {
        val paymentId = unknownAttempt("4503")

        val result = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(PaymentCommand.ResolveUnknown.Result(paymentId, PaymentAttemptStatus.DECLINED), result)
        val row = row(paymentId)
        assertEquals(PaymentAttemptStatus.DECLINED.name, row["status"])
        assertNotNull(row["provider_reference"])
        assertEquals(1, declinedEvents.countFor(paymentId))
    }

    @Test
    fun `조회로도 불명이면 query_count만 하나 오르고 상태와 참조와 발행 기록은 바뀌지 않는다`() {
        val paymentId = unknownAttempt("4504")
        val unknownPublications = publications(paymentId, "Unknown")

        val result = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(PaymentCommand.ResolveUnknown.Result(paymentId, PaymentAttemptStatus.UNKNOWN), result)
        val row = row(paymentId)
        assertEquals(PaymentAttemptStatus.UNKNOWN.name, row["status"])
        assertNull(row["provider_reference"])
        assertEquals(1, row["query_count"])
        assertNull(row["reconciliation_requested_at"])
        assertEquals(unknownPublications, publications(paymentId, "Unknown"))
        assertEquals(0, publications(paymentId, "PaymentApproved"))
        assertEquals(0, publications(paymentId, "PaymentReconciliationRequested"))
        assertEquals(0, declinedEvents.countFor(paymentId))
    }

    @Test
    fun `조회 불명이 상한에 닿으면 넘긴 시각이 정해지고 QUERY_LIMIT_EXCEEDED 발행 기록이 같은 커밋에 남고 그 뒤로 조회하지 않는다`() {
        val paymentId = unknownAttempt("4514")

        repeat(2) { paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId)) }
        // 상한(3) 전에는 횟수만 오르고 발행 기록이 없다.
        assertEquals(2, row(paymentId)["query_count"])
        assertNull(row(paymentId)["reconciliation_requested_at"])
        assertEquals(0, publications(paymentId, "PaymentReconciliationRequested"))

        val result = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(PaymentCommand.ResolveUnknown.Result(paymentId, PaymentAttemptStatus.UNKNOWN), result)
        val row = row(paymentId)
        assertEquals(3, row["query_count"])
        assertNotNull(row["reconciliation_requested_at"])
        // 발행 기록이 넘김과 같은 커밋에 들어갔으므로 반환 직후 이미 있다.
        assertEquals(1, publications(paymentId, "PaymentReconciliationRequested"))
        val fact = serializedFact(paymentId)
        assertTrue("QUERY_LIMIT_EXCEEDED" in fact && "RESULT_UNCONFIRMED" in fact && "\"queryCount\":3" in fact, fact)
        // 토픽이 선언돼 있어야 브로커로 나가 발행 기록이 완료된다(자동 토픽 생성은 꺼져 있다).
        awaitUntil("넘김 발행 기록이 브로커 전송을 마친다") { completedPublications(paymentId) == 1 }

        // 넘긴 시도는 직접 넘겨도 provider를 부르지 않는다.
        val queriesAtHandOver = gateway.queryCallsFor(paymentId)
        val again = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))
        assertEquals(PaymentAttemptStatus.UNKNOWN, again.status)
        assertEquals(queriesAtHandOver, gateway.queryCallsFor(paymentId))
        assertEquals(3, row(paymentId)["query_count"])
        assertEquals(1, publications(paymentId, "PaymentReconciliationRequested"))
    }

    @Test
    fun `이전 버전이 만든 unknown_reason이 null인 UNKNOWN 행도 상한에서 넘기고 사유는 RESULT_UNCONFIRMED로 읽는다`() {
        // 롤링 배포 중 이전 버전이 만든 UNKNOWN 행을 흉내 낸다. 끝자리 4는 Fake가 조회에도 결과를 알리지 않는다.
        val paymentId = PaymentId(UUID.randomUUID())
        jdbcTemplate.update(
            """
            insert into t_payment_attempt
                (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status, unknown_reason, created_at, updated_at)
            values (?, ?, ?, ?, 1, 'AUTHORIZATION', 4534, 'KRW', 'UNKNOWN', null, now(), now())
            """.trimIndent(),
            paymentId.value, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        )

        repeat(3) { paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId)) }

        assertNotNull(row(paymentId)["reconciliation_requested_at"])
        assertEquals(1, publications(paymentId, "PaymentReconciliationRequested"))
        val fact = serializedFact(paymentId)
        assertTrue("\"unknownReason\":\"RESULT_UNCONFIRMED\"" in fact, fact)
    }

    @Test
    fun `조회가 다른 금액의 승인이면 확정하지 않고 바로 넘기고 AMOUNT_MISMATCH 발행 기록에 알려 준 금액과 참조가 남는다`() {
        val paymentId = unknownAttempt("4525")

        val result = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(PaymentCommand.ResolveUnknown.Result(paymentId, PaymentAttemptStatus.UNKNOWN), result)
        val row = row(paymentId)
        assertEquals(PaymentAttemptStatus.UNKNOWN.name, row["status"])
        assertNull(row["provider_reference"])
        assertEquals(1, row["query_count"])
        assertNotNull(row["reconciliation_requested_at"])
        assertEquals(0, publications(paymentId, "PaymentApproved"))
        assertEquals(1, publications(paymentId, "PaymentReconciliationRequested"))
        val fact = serializedFact(paymentId)
        assertTrue("AMOUNT_MISMATCH" in fact && "4526" in fact && "FAKE-" in fact, fact)
    }

    @Test
    fun `승인 요청에서 UNKNOWN(AMOUNT_MISMATCH)이 된 시도도 조회에서 다른 금액이 오면 넘어간다`() {
        val paymentId = unknownAttempt("4536")
        assertEquals("AMOUNT_MISMATCH", row(paymentId)["unknown_reason"])

        paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertNotNull(row(paymentId)["reconciliation_requested_at"])
        val fact = serializedFact(paymentId)
        assertTrue("\"reason\":\"AMOUNT_MISMATCH\"" in fact && "\"unknownReason\":\"AMOUNT_MISMATCH\"" in fact, fact)
    }

    @Test
    fun `결말이 난 시도는 provider를 조회하지 않고 지금 상태를 돌려준다`() {
        val paymentId = unknownAttempt("4512")
        paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))
        val queriesAfterResolve = gateway.queryCallsFor(paymentId)

        val again = paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(PaymentAttemptStatus.APPROVED, again.status)
        assertEquals(queriesAfterResolve, gateway.queryCallsFor(paymentId))
        assertEquals(1, publications(paymentId, "PaymentApproved"))
    }

    @Test
    fun `provider를 조회하는 동안 DB 자원을 쥐지 않는다`() {
        val paymentId = unknownAttempt("4522")

        paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))

        assertEquals(1, gateway.queryCallsFor(paymentId))
        assertEquals(false, gateway.resourceBoundDuringQuery(paymentId))
    }

    /** 금액 불일치로 reconciliation에 넘어간 시도를 만든다. */
    private fun handedOverAttempt(unitPrice: String): PaymentId {
        val paymentId = unknownAttempt(unitPrice)
        paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))
        assertNotNull(row(paymentId)["reconciliation_requested_at"])
        return paymentId
    }

    private fun conclude(paymentId: PaymentId) = paymentManagement.resolveFromReconciliation(
        PaymentCommand.ResolveFromReconciliation(paymentId, PaymentCommand.ResolveFromReconciliation.Conclusion.APPROVED, "RECON-REF"),
    )

    @Test
    fun `호출자 트랜잭션 안에서 넘긴 시도에 승인 결론을 적용하면 APPROVED와 참조와 승인 발행 기록이 같은 커밋에 남는다`() {
        val paymentId = handedOverAttempt("4535")
        assertEquals(0, publications(paymentId, "PaymentApproved"))

        val result = Tx.masterTx { conclude(paymentId) }

        assertEquals(PaymentCommand.ResolveFromReconciliation.Result(paymentId, PaymentAttemptStatus.APPROVED), result)
        val row = row(paymentId)
        assertEquals(PaymentAttemptStatus.APPROVED.name, row["status"])
        assertEquals("RECON-REF", row["provider_reference"])
        assertEquals(1, publications(paymentId, "PaymentApproved"))
    }

    @Test
    fun `호출자 트랜잭션이 롤백되면 결론도 발행 기록도 남지 않는다`() {
        val paymentId = handedOverAttempt("4545")

        assertFailsWith<IllegalStateException> {
            Tx.masterTx {
                conclude(paymentId)
                throw IllegalStateException("호출자가 롤백한다")
            }
        }

        val row = row(paymentId)
        assertEquals(PaymentAttemptStatus.UNKNOWN.name, row["status"])
        assertNull(row["provider_reference"])
        assertEquals(0, publications(paymentId, "PaymentApproved"))
    }
}
