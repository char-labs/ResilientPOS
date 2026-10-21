package com.resilientpos.reconciliation.internal.adapter.web

import com.jayway.jsonpath.JsonPath
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.payment.PaymentId
import com.resilientpos.platform.OwnerSession
import com.resilientpos.platform.asOwner
import com.resilientpos.platform.logInNewOwner
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationFixtures
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.caseRow
import com.resilientpos.reconciliation.isUnresolved
import com.resilientpos.reconciliation.attemptRow
import com.resilientpos.reconciliation.caseStatus
import com.resilientpos.reconciliation.insertHandedOverAttempt
import com.resilientpos.store.StoreId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 실제 점주 세션·CSRF 체인, 매장 소유 확인, 대조 case 목록·해소, 결제 확정, 감사 로그를 HTTP부터 DB까지 잇는다. Docker가 필요하다.
 * case는 `ReconciliationManagement.open`으로 만들고(넘김 사실을 받은 상태), 시도는 SQL로 넘긴 `UNKNOWN` 행을 넣는다.
 * DB를 다른 테스트와 함께 쓰므로 단언은 이 테스트가 만든 매장·case·시도만 본다.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ReconciliationCaseOwnerApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val management: ReconciliationManagement,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private val owner: OwnerSession by lazy { mockMvc.logInNewOwner() }
    private val storeId: UUID by lazy { createStore(owner) }

    private fun createStore(session: OwnerSession): UUID = UUID.fromString(
        mockMvc.post("/owner/v1/stores") {
            asOwner(session)
            contentType = MediaType.APPLICATION_JSON
            content = """{ "name": "강남점" }"""
        }.andExpect { status { isCreated() } }.let { JsonPath.read<String>(it.andReturn().response.contentAsString, "$.data.storeId") },
    )

    /** 그 매장의 `MANUAL_REVIEW` case와 넘긴 `UNKNOWN` 시도를 만든다. */
    private fun openCase(store: UUID, reason: PaymentReconciliationReason = PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED): Pair<ReconciliationCaseId, PaymentId> {
        val paymentId = jdbcTemplate.insertHandedOverAttempt()
        val command = ReconciliationFixtures.open(reason = reason).copy(paymentId = paymentId, orderId = UUID.randomUUID(), storeId = StoreId(store))
        return management.open(command).caseId to paymentId
    }

    private fun resolveUrl(store: UUID, caseId: ReconciliationCaseId) = "/owner/v1/stores/$store/reconciliation-cases/${caseId.value}/resolve"

    private fun approve(amount: String = "4500", store: UUID = storeId, caseId: ReconciliationCaseId, session: OwnerSession = owner): ResultActionsDsl =
        resolve(store, caseId, session, """{ "decision": "APPROVED", "providerReference": "PG-REF-1", "approvedAmount": "$amount", "approvedCurrency": "KRW", "note": "PG 화면에서 승인 확인" }""")

    private fun decline(caseId: ReconciliationCaseId): ResultActionsDsl =
        resolve(storeId, caseId, owner, """{ "decision": "DECLINED", "note": "PG 화면에서 거절 확인" }""")

    private fun resolve(store: UUID, caseId: ReconciliationCaseId, session: OwnerSession, body: String): ResultActionsDsl =
        mockMvc.post(resolveUrl(store, caseId)) {
            asOwner(session)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun auditLines(output: CapturedOutput, caseId: ReconciliationCaseId) =
        output.all.lines().filter { "action=RECONCILIATION_CASE_RESOLVED" in it && "caseId=${caseId.value}" in it }

    @Test
    fun `목록은 자기 매장의 MANUAL_REVIEW case만 최신순으로 돌려주고 다른 매장과 해소된 case는 없다`() {
        val (older, _) = openCase(storeId)
        val (resolved, _) = openCase(storeId)
        val (newer, newerPayment) = openCase(storeId)
        val otherStore = createStore(mockMvc.logInNewOwner())
        openCase(otherStore)
        approve(caseId = resolved).andExpect { status { isOk() } }

        mockMvc.get("/owner/v1/stores/$storeId/reconciliation-cases") { asOwner(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].caseId") { value(newer.value.toString()) }
            jsonPath("$.data[1].caseId") { value(older.value.toString()) }
            jsonPath("$.data[0].paymentId") { value(newerPayment.value.toString()) }
            jsonPath("$.data[0].status") { value("MANUAL_REVIEW") }
            jsonPath("$.data[0].reason") { value("QUERY_LIMIT_EXCEEDED") }
            jsonPath("$.data[0].unknownReason") { value("RESULT_UNCONFIRMED") }
            jsonPath("$.data[0].ruleVersion") { value(1) }
            jsonPath("$.data[0].amount") { value("4500") }
            jsonPath("$.data[0].currency") { value("KRW") }
            jsonPath("$.data[0].reportedAmount") { value(null) }
            jsonPath("$.data[0].openedAt") { value(ReconciliationFixtures.handedAt.toString()) }
        }
    }

    @Test
    fun `승인으로 해소하면 case는 RESOLVED가 되고 시도는 APPROVED가 되며 같은 결론의 재요청은 같은 결과에 해소 기록도 그대로이고 다른 결론은 409다`(output: CapturedOutput) {
        val (caseId, paymentId) = openCase(storeId)

        approve(caseId = caseId).andExpect {
            status { isOk() }
            jsonPath("$.data.caseId") { value(caseId.value.toString()) }
            jsonPath("$.data.status") { value("RESOLVED") }
            jsonPath("$.data.decision") { value("APPROVED") }
            jsonPath("$.data.paymentId") { value(paymentId.value.toString()) }
            jsonPath("$.data.paymentStatus") { value("APPROVED") }
        }

        assertEquals("RESOLVED", jdbcTemplate.caseStatus(caseId))
        assertEquals("APPROVED", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals("PG-REF-1", jdbcTemplate.attemptRow(paymentId)["provider_reference"])
        val resolvedRow = jdbcTemplate.caseRow(caseId)
        assertEquals(owner.ownerId, resolvedRow["resolved_by_owner_id"])
        assertEquals("APPROVED", resolvedRow["decision"])
        assertEquals("PG-REF-1", resolvedRow["provider_reference"])
        val audit = auditLines(output, caseId).single()
        listOf("ownerId=${owner.ownerId}", "storeId=$storeId", "paymentId=${paymentId.value}", "decision=APPROVED", "paymentStatus=APPROVED")
            .forEach { assertTrue(it in audit, "$it 가 감사 줄에 없다: $audit") }
        assertFalse("PG 화면에서 승인 확인" in output.all, "사유 원문이 로그에 남았다")

        approve(caseId = caseId).andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("RESOLVED") }
            jsonPath("$.data.decision") { value("APPROVED") }
            jsonPath("$.data.paymentStatus") { value("APPROVED") }
        }
        assertEquals(resolvedRow, jdbcTemplate.caseRow(caseId))
        assertEquals(1, auditLines(output, caseId).size, "같은 결론의 재요청에는 감사 줄이 남지 않는다")

        decline(caseId).andExpect {
            status { isConflict() }
            jsonPath("$.error.errorCode") { value("RECONCILIATION_CASE_ALREADY_RESOLVED") }
        }
        assertEquals(1, auditLines(output, caseId).size)
        assertEquals("APPROVED", jdbcTemplate.attemptRow(paymentId)["status"])
        assertEquals(resolvedRow, jdbcTemplate.caseRow(caseId))
    }

    @Test
    fun `승인 금액이 다르면 422이고 case와 시도와 해소 기록이 바뀌지 않으며 참조가 없으면 400이다`(output: CapturedOutput) {
        val (caseId, paymentId) = openCase(storeId)

        approve(amount = "4600", caseId = caseId).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.errorCode") { value("RECONCILIATION_AMOUNT_MISMATCH") }
        }
        resolve(storeId, caseId, owner, """{ "decision": "APPROVED", "approvedAmount": "4500", "approvedCurrency": "KRW", "note": "확인" }""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("INVALID_RECONCILIATION_RESOLUTION") }
        }

        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])
        assertTrue(jdbcTemplate.isUnresolved(caseId))
        assertEquals(emptyList(), auditLines(output, caseId))
    }

    @Test
    fun `금액 불일치 case의 거절은 PG 취소 참조가 없으면 400이고 바뀌지 않으며 참조가 있으면 시도가 DECLINED가 된다`() {
        val (caseId, paymentId) = openCase(storeId, PaymentReconciliationReason.AMOUNT_MISMATCH)

        decline(caseId).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("INVALID_RECONCILIATION_RESOLUTION") }
        }
        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])

        resolve(storeId, caseId, owner, """{ "decision": "DECLINED", "providerReference": "PG-CANCEL-1", "note": "PG에서 취소 확인" }""").andExpect {
            status { isOk() }
            jsonPath("$.data.paymentStatus") { value("DECLINED") }
        }
        assertEquals("PG-CANCEL-1", jdbcTemplate.attemptRow(paymentId)["provider_reference"])
    }

    @Test
    fun `다른 매장의 case를 자기 매장 경로로 해소하면 404 RECONCILIATION_CASE_NOT_FOUND이고 남의 매장 경로는 404 STORE_NOT_FOUND이며 아무것도 바뀌지 않는다`() {
        val intruder = mockMvc.logInNewOwner()
        val intruderStore = createStore(intruder)
        val (caseId, paymentId) = openCase(storeId)

        approve(store = storeId, caseId = openCase(intruderStore).first).andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("RECONCILIATION_CASE_NOT_FOUND") }
        }
        approve(store = storeId, caseId = ReconciliationCaseId(UUID.randomUUID())).andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("RECONCILIATION_CASE_NOT_FOUND") }
        }
        approve(store = storeId, caseId = caseId, session = intruder).andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("STORE_NOT_FOUND") }
        }
        mockMvc.get("/owner/v1/stores/$storeId/reconciliation-cases") { asOwner(intruder) }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("STORE_NOT_FOUND") }
        }

        assertEquals("MANUAL_REVIEW", jdbcTemplate.caseStatus(caseId))
        assertEquals("UNKNOWN", jdbcTemplate.attemptRow(paymentId)["status"])
    }
}
