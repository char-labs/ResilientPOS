package com.resilientpos.reconciliation.internal.application

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.createAndRequestCheckout
import com.resilientpos.order.orderStatus
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentFixtures
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.internal.adapter.gateway.ScriptedPaymentGateway
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.ReconciliationFixtures
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.caseRow
import com.resilientpos.reconciliation.attemptRow
import com.resilientpos.reconciliation.caseCountFor
import com.resilientpos.reconciliation.publicationCount
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate

/**
 * 조회로도 확정되지 않은 시도가 Kafka를 거쳐 case가 되고, 점주의 해소가 주문까지 수렴하는 것을 끝까지 태운다.
 * Fake의 끝자리 4는 상한(테스트 3)까지 조회한 뒤, 끝자리 5는 한 번의 조회로 금액 불일치로 넘어간다. 금액은 테스트마다 달라야 한다.
 * 주기 작업은 스스로 돌지 않으므로(`PostgresIntegrationTest`) `resolveUnknown`을 직접 부른다.
 * DB를 다른 테스트와 함께 쓰므로 단언은 이 테스트가 만든 주문·시도·case만 본다.
 */
class ReconciliationFlowIntegrationTest(
    private val orderFacade: OrderCommandFacade,
    private val paymentManagement: PaymentManagement,
    private val reconciliationManagement: ReconciliationManagement,
    private val jdbcTemplate: JdbcTemplate,
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val gateway: ScriptedPaymentGateway,
) : PostgresIntegrationTest() {

    private val created = mutableListOf<UUID>()

    @AfterTest
    fun cleanUp() {
        // 해소하지 않고 남긴 시도가 다른 테스트의 작업에 집히지 않게 닫아 둔다.
        created.forEach { jdbcTemplate.update("update t_payment_attempt set status = 'DECLINED' where order_id = ? and status = 'UNKNOWN'", it) }
    }

    private fun checkoutOrder(unitPrice: String): UUID =
        orderFacade.createAndRequestCheckout(OrderFixtures.createOrderCommand(lines = listOf(OrderFixtures.line(unitPrice)))).value.also { created += it }

    private fun attempts(orderId: UUID): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select id, status from t_payment_attempt where order_id = ? order by id", orderId)

    private fun caseId(paymentId: PaymentId): ReconciliationCaseId? =
        jdbcTemplate.queryForList("select id from t_reconciliation_case where payment_id = ?", paymentId.value)
            .singleOrNull()?.let { ReconciliationCaseId(it["id"] as UUID) }

    /** 주문을 checkout해 시도가 `UNKNOWN`이 되면 상한까지 조회해 넘기고, case가 열릴 때까지 기다려 시도와 case를 돌려준다. */
    private fun handedOver(unitPrice: String, queries: Int): Pair<PaymentId, ReconciliationCaseId> {
        val orderId = checkoutOrder(unitPrice)
        awaitUntil("시도가 UNKNOWN이 된다. 현재: ${attempts(orderId)}") { attempts(orderId).singleOrNull()?.get("status") == "UNKNOWN" }
        val paymentId = PaymentId(attempts(orderId).single()["id"] as UUID)
        // 기본 동작은 같은 금액의 재결제를 다시 결과 불명으로 만든다. 첫 시도가 아닌 이 금액의 승인(재결제)은 승인하게 건다.
        gateway.approveRetriesOf(paymentId, Money.of(unitPrice, OrderFixtures.krw))
        repeat(queries) { paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId)) }
        awaitUntil("case가 열린다") { caseId(paymentId) != null }
        return paymentId to checkNotNull(caseId(paymentId))
    }

    private fun resend(orderId: UUID) {
        KafkaTestSupport.records(TOPIC, orderId).forEach { kafkaTemplate.send(ProducerRecord<Any, Any>(TOPIC, it.key(), it.value().toByteArray())).get() }
        KafkaTestSupport.awaitConsumed(GROUP, TOPIC)
    }

    @Test
    fun `상한까지 조회가 불명이면 case가 MANUAL_REVIEW와 규칙 버전 1과 넘긴 시각의 연 시각으로 열리고 같은 사실을 다시 받아도 하나다`() {
        val (paymentId, caseId) = handedOver("4524", queries = 3)

        val row = jdbcTemplate.queryForList("select status, rule_version, reason, order_id from t_reconciliation_case where id = ?", caseId.value).single()
        assertEquals("MANUAL_REVIEW", row["status"])
        assertEquals(1, row["rule_version"])
        assertEquals("QUERY_LIMIT_EXCEEDED", row["reason"])
        val openedRow = jdbcTemplate.caseRow(caseId)
        assertNotNull(openedRow["opened_at"])
        assertNull(openedRow["resolved_at"])

        resend(row["order_id"] as UUID)

        assertEquals(1, jdbcTemplate.caseCountFor(paymentId))
        assertEquals(openedRow, jdbcTemplate.caseRow(caseId))
    }

    @Test
    fun `조회가 다른 금액의 승인이면 AMOUNT_MISMATCH case가 알려 준 금액과 참조와 함께 열린다`() {
        val (_, caseId) = handedOver("4535", queries = 1)

        val row = jdbcTemplate.queryForList(
            "select status, reason, reported_amount, reported_currency, reported_provider_reference from t_reconciliation_case where id = ?",
            caseId.value,
        ).single()
        assertEquals("MANUAL_REVIEW", row["status"])
        assertEquals("AMOUNT_MISMATCH", row["reason"])
        assertEquals(0, java.math.BigDecimal("4536").compareTo(row["reported_amount"] as java.math.BigDecimal))
        assertEquals("KRW", row["reported_currency"])
        assertNotNull(row["reported_provider_reference"])
    }

    @Test
    fun `점주가 승인으로 해소하면 시도가 APPROVED가 되고 주문이 PAID가 된다`() {
        val (paymentId, caseId) = handedOver("4544", queries = 3)

        reconciliationManagement.resolve(
            ReconciliationFixtures.resolve(
                caseId,
                providerReference = "PG-REF-FLOW",
                approvedAmount = Money.of("4544", OrderFixtures.krw),
            ).copy(storeId = OrderFixtures.storeId),
        )

        assertEquals("RESOLVED", jdbcTemplate.queryForList("select status from t_reconciliation_case where id = ?", caseId.value).single()["status"])
        assertEquals(ReconciliationFixtures.ownerId.value, jdbcTemplate.caseRow(caseId)["resolved_by_owner_id"])
        assertEquals(mapOf<String, Any?>("status" to "APPROVED", "provider_reference" to "PG-REF-FLOW"), jdbcTemplate.attemptRow(paymentId))
        assertEquals(1, jdbcTemplate.publicationCount(paymentId, "PaymentApproved"))
        val orderId = jdbcTemplate.queryForObject("select order_id from t_payment_attempt where id = ?", UUID::class.java, paymentId.value)!!
        awaitUntil("주문이 PAID가 된다. 현재: ${jdbcTemplate.orderStatus(orderId)}") { jdbcTemplate.orderStatus(orderId) == "PAID" }
    }

    @Test
    fun `점주가 거절로 해소하면 시도가 DECLINED이고 주문은 CHECKOUT_REQUESTED이며 재결제가 승인되면 PAID가 된다`() {
        // 끝자리 5는 금액 불일치 case라 거절에 PG 취소 참조가 필요하다.
        val (paymentId, caseId) = handedOver("4555", queries = 1)
        val orderId = jdbcTemplate.queryForObject("select order_id from t_payment_attempt where id = ?", UUID::class.java, paymentId.value)!!

        val result = reconciliationManagement.resolve(
            ReconciliationFixtures.resolve(caseId, ReconciliationDecision.DECLINED, providerReference = "PG-CANCEL-FLOW", approvedAmount = null)
                .copy(storeId = OrderFixtures.storeId),
        )

        assertEquals(PaymentAttemptStatus.DECLINED, result.paymentStatus)
        assertEquals("DECLINED", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals("CHECKOUT_REQUESTED", jdbcTemplate.orderStatus(orderId))

        paymentManagement.retryAuthorization(PaymentFixtures.retryAuthorizationCommand(paymentId, OrderFixtures.storeId))

        awaitUntil("주문이 PAID가 된다. 현재: ${jdbcTemplate.orderStatus(orderId)} ${attempts(orderId)}") { jdbcTemplate.orderStatus(orderId) == "PAID" }
        assertEquals(listOf("DECLINED", "APPROVED"), attempts(orderId).map { it["status"] })
    }

    private companion object {
        const val TOPIC = "payment.reconciliation-requested"
        const val GROUP = "reconciliation.payment-reconciliation-requested"
    }
}
