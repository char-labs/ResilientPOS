package com.resilientpos.reconciliation

import com.resilientpos.payment.PaymentId
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 통합 테스트가 저장된 case·시도를 Facade 결과가 아니라 커밋된 행으로 확인할 때 쓰는 조회와 준비.
 * 시도는 SQL로 직접 넣는다. 이벤트로 만들면 승인 리스너가 끼어들고 Kafka를 기다려야 한다.
 * 임의 order_id라 승인 쪽이 이기면 `PaymentApprovedListener`가 주문을 찾지 못해 DLT로 보낸다(테스트 잡음, 단언과 무관).
 */
fun JdbcTemplate.insertHandedOverAttempt(status: String = "UNKNOWN", providerReference: String? = null): PaymentId {
    val id = UUID.randomUUID()
    update(
        """
        insert into t_payment_attempt
            (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status,
             provider_reference, unknown_reason, query_count, reconciliation_requested_at, created_at, updated_at)
        values (?, ?, ?, ?, 1, 'AUTHORIZATION', 4500, 'KRW', ?, ?, 'RESULT_UNCONFIRMED', 3, now(), now(), now())
        """.trimIndent(),
        id, UUID.randomUUID(), ReconciliationFixtures.storeId.value, ReconciliationFixtures.terminalId.value, status, providerReference,
    )
    return PaymentId(id)
}

/** 시도를 열기 전 상태로 되돌린 것처럼 넘김 표시가 없는 `UNKNOWN` 시도. */
fun JdbcTemplate.insertUnhandedUnknownAttempt(): PaymentId =
    insertHandedOverAttempt().also { update("update t_payment_attempt set reconciliation_requested_at = null where id = ?", it.value) }

fun JdbcTemplate.attemptRow(paymentId: PaymentId): Map<String, Any?> =
    queryForList("select status, provider_reference from t_payment_attempt where id = ?", paymentId.value).single()

fun JdbcTemplate.caseStatus(caseId: ReconciliationCaseId): String? =
    queryForObject<String>("select status from t_reconciliation_case where id = ?", caseId.value)

fun JdbcTemplate.caseCountFor(paymentId: PaymentId): Int =
    checkNotNull(queryForObject<Int>("select count(*) from t_reconciliation_case where payment_id = ?", paymentId.value))

/** case 행의 열린 시각과 해소 기록 컬럼. 해소 전이면 해소 컬럼이 모두 null이다. */
fun JdbcTemplate.caseRow(caseId: ReconciliationCaseId): Map<String, Any?> =
    queryForList(
        "select status, rule_version, opened_at, resolved_at, resolved_by_owner_id, decision, provider_reference, approved_amount, approved_currency, note " +
            "from t_reconciliation_case where id = ?",
        caseId.value,
    ).single()

/** 해소 기록 컬럼이 모두 비어 있는가. 해소 전이거나 해소가 롤백된 case다. */
fun JdbcTemplate.isUnresolved(caseId: ReconciliationCaseId): Boolean =
    caseRow(caseId).filterKeys { it in RESOLUTION_COLUMNS }.values.all { it == null }

private val RESOLUTION_COLUMNS = setOf("resolved_at", "resolved_by_owner_id", "decision", "provider_reference", "approved_amount", "approved_currency", "note")

/** 그 시도의 사실 중 `eventSuffix`로 끝나는 타입의 발행 기록 수. 직렬화한 이벤트에 `paymentId`가 들어 있다. */
fun JdbcTemplate.publicationCount(paymentId: PaymentId, eventSuffix: String): Int =
    checkNotNull(
        queryForObject<Int>(
            "select count(*) from event_publication where event_type like ? and serialized_event like ?",
            "%$eventSuffix",
            "%${paymentId.value}%",
        ),
    )
