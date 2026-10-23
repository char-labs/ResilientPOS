package com.resilientpos.reconciliation.internal.application

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentReconciliationRequested
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.PaymentId
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationFixtures
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.caseCountFor
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.reconciliation.internal.domain.ReconciliationCaseRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 같은 시도의 case 열기가 겹칠 때 case가 하나로 수렴하는지 본다. 열기는 "있나 확인 → 넣기"라 둘 다 "없음"을 읽을 수 있고,
 * 막는 것은 unique `ux_reconciliation_case_payment`와 consumer의 Kafka 재시도다.
 *
 * 겹침은 `PaymentAttemptCreationConcurrencyIntegrationTest`와 같은 방법으로 결정적으로 만든다. 뒤쪽(B)은 "없음"을 읽고 넣기만 남은 요청이다.
 * 바깥 트랜잭션이 B의 커밋을 붙잡고, 그동안 앞쪽(A)을 다른 스레드에서 끝까지 커밋시킨다. B의 삽입은 커밋에서 flush되므로 A는 B를 기다리지 않는다.
 */
class ReconciliationOpenConcurrencyIntegrationTest(
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val repository: ReconciliationCaseRepository,
    @Autowired private val identifierGenerator: IdentifierGenerator,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    // 테스트 기반 클래스가 spy로 둔다.
    @Autowired private val management: ReconciliationManagement,
) : PostgresIntegrationTest() {

    private fun openCommand(paymentId: PaymentId, orderId: UUID = UUID.randomUUID()) =
        ReconciliationFixtures.open().copy(paymentId = paymentId, orderId = orderId)

    /** Mockito의 `any`는 null을 돌려주어 Kotlin의 non-null 검사에 걸린다. 매처만 등록하고 검사를 건너뛴다. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(type: Class<T>): T {
        any(type)
        return null as T
    }

    @Test
    fun `같은 시도의 열기가 겹쳐 한쪽이 unique 위반으로 실패해도 다시 부르면 기존 case를 돌려주고 case는 하나다`() {
        val paymentId = PaymentId(UUID.randomUUID())
        val command = openCommand(paymentId)
        val executor = Executors.newSingleThreadExecutor()

        try {
            lateinit var winner: ReconciliationCommand.Open.Result
            val lost = assertFailsWith<DataIntegrityViolationException> {
                transactions.executeWithoutResult {
                    repository.save(ReconciliationCase.open(command, identifierGenerator).aggregate)
                    winner = executor.submit<ReconciliationCommand.Open.Result> { management.open(command) }.get(10, TimeUnit.SECONDS)
                }
            }

            assertContains(lost.mostSpecificCause.message.orEmpty(), "ux_reconciliation_case_payment")
            assertEquals(1, jdbcTemplate.caseCountFor(paymentId))
            assertEquals(winner, management.open(command))
            assertEquals(1, jdbcTemplate.caseCountFor(paymentId))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `consumer의 열기가 unique 위반으로 실패하면 DLT로 가지 않고 재시도가 기존 case를 찾아 case는 하나다`() {
        val paymentId = PaymentId(UUID.randomUUID())
        val orderId = UUID.randomUUID()
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        // 첫 호출(consumer)만 겹침을 만든다. 같은 시도를 다른 스레드의 A가 먼저 커밋하고, 바깥 트랜잭션이 커밋될 때 B가 unique에 걸려 던진다.
        // 두 번째 호출(A)과 consumer의 재시도(세 번째)는 실제 동작을 탄다.
        doAnswer { invocation ->
            if (calls.incrementAndGet() == 1) {
                transactions.executeWithoutResult {
                    invocation.callRealMethod()
                    executor.submit { management.open(invocation.getArgument<ReconciliationCommand.Open>(0)) }.get(10, TimeUnit.SECONDS)
                }
            }
            invocation.callRealMethod()
        }.`when`(management).open(anyOf(ReconciliationCommand.Open::class.java))

        try {
            transactions.executeWithoutResult {
                eventPublisher.publishEvent(
                    PaymentReconciliationRequested(
                        eventId = UUID.randomUUID(),
                        occurredAt = Instant.now(),
                        paymentId = paymentId.value,
                        orderId = orderId,
                        storeId = ReconciliationFixtures.storeId,
                        terminalId = ReconciliationFixtures.terminalId,
                        checkoutNo = 1,
                        amount = ReconciliationFixtures.amount,
                        reason = PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED,
                        unknownReason = PaymentUnknownReason.RESULT_UNCONFIRMED,
                        queryCount = 3,
                        reportedAmount = null,
                        reportedProviderReference = null,
                    ),
                )
            }

            awaitUntil("consumer가 재시도까지 처리한다") { calls.get() >= 3 }
            KafkaTestSupport.awaitConsumed("reconciliation.payment-reconciliation-requested", PaymentReconciliationRequested.TOPIC)
            assertEquals(3, calls.get(), "consumer 첫 처리, 다른 스레드의 A, consumer의 재시도")
            assertEquals(1, jdbcTemplate.caseCountFor(paymentId))
            assertEquals(emptyList(), KafkaTestSupport.records("${PaymentReconciliationRequested.TOPIC}.DLT", orderId))
        } finally {
            executor.shutdownNow()
        }
    }
}
