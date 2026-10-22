package com.resilientpos.reconciliation.internal.adapter.web

import com.resilientpos.contract.PaymentReconciliationReason
import com.resilientpos.contract.PaymentUnknownReason
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentId
import com.resilientpos.platform.withCsrf
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.reconciliation.ReconciliationCaseStatus
import com.resilientpos.reconciliation.ReconciliationCaseView
import com.resilientpos.reconciliation.ReconciliationCommand
import com.resilientpos.reconciliation.ReconciliationDecision
import com.resilientpos.reconciliation.ReconciliationManagement
import com.resilientpos.reconciliation.ReconciliationQuery
import com.resilientpos.reconciliation.ReconciliationQueryFacade
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import com.resilientpos.terminal.MockTerminalRateLimiter
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 점주 대조 case API 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·validation·오류 변환·문서 계약만 검사한다.
 * 인증과 CSRF는 `TerminalOwnerControllerTest`와 같은 방식이다(UUID 문자열 principal, 운영과 같은 CSRF 쿠키·헤더).
 */
@MockTerminalRateLimiter
@WebMvcTest(ReconciliationCaseOwnerController::class)
@AutoConfigureRestDocs
class ReconciliationCaseOwnerControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var management: ReconciliationManagement

    @MockitoBean
    private lateinit var queryFacade: ReconciliationQueryFacade

    @MockitoBean
    private lateinit var stores: StoreManagement

    /** 슬라이스가 싣는 단말 인증 인터셉터의 의존성이다. 이 API는 단말을 쓰지 않는다. */
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    private val owner: RequestPostProcessor = authentication(UsernamePasswordAuthenticationToken.authenticated(OWNER_ID, null, emptyList()))

    private fun resolve(body: String, caseId: String = CASE_ID): ResultActions =
        mockMvc.perform(
            post("/owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve", STORE_ID, caseId)
                .with(owner).withCsrf().contentType(MediaType.APPLICATION_JSON).content(body),
        )

    private fun list(): ResultActions = mockMvc.perform(get("/owner/v1/stores/{storeId}/reconciliation-cases", STORE_ID).with(owner))

    private fun approveBody(note: String = NOTE) = """
        { "decision": "APPROVED", "providerReference": "$PROVIDER_REF", "approvedAmount": "4500", "approvedCurrency": "KRW", "note": "$note" }
    """.trimIndent()

    private fun domainError(code: String, kind: DomainErrorKind) = DomainException(code, "오류", kind)

    private fun givenResolveFails(error: DomainException) {
        given(management.resolve(APPROVE)).willThrow(error)
    }

    @Test
    fun `목록은 200과 미해소 case를 최신순 그대로 돌려주고 금액은 통화 기준 문자열이다`() {
        given(queryFacade.findReviewable(ReconciliationQuery.FindReviewable(StoreId(UUID.fromString(STORE_ID))))).willReturn(
            listOf(
                view(CASE_ID, PaymentReconciliationReason.AMOUNT_MISMATCH, reported = Money.of("4600", KRW), reportedRef = "PG-REF-9"),
                view(OTHER_CASE_ID, PaymentReconciliationReason.QUERY_LIMIT_EXCEEDED),
            ),
        )

        list()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].caseId").value(CASE_ID))
            .andExpect(jsonPath("$.data[0].paymentId").value(PAYMENT_ID))
            .andExpect(jsonPath("$.data[0].orderId").value(ORDER_ID))
            .andExpect(jsonPath("$.data[0].terminalId").value(TERMINAL_ID))
            .andExpect(jsonPath("$.data[0].checkoutNo").value(1))
            .andExpect(jsonPath("$.data[0].reason").value("AMOUNT_MISMATCH"))
            .andExpect(jsonPath("$.data[0].unknownReason").value("RESULT_UNCONFIRMED"))
            .andExpect(jsonPath("$.data[0].status").value("MANUAL_REVIEW"))
            .andExpect(jsonPath("$.data[0].ruleVersion").value(1))
            .andExpect(jsonPath("$.data[0].amount").value("4500"))
            .andExpect(jsonPath("$.data[0].currency").value("KRW"))
            .andExpect(jsonPath("$.data[0].queryCount").value(3))
            .andExpect(jsonPath("$.data[0].reportedAmount").value("4600"))
            .andExpect(jsonPath("$.data[0].reportedCurrency").value("KRW"))
            .andExpect(jsonPath("$.data[0].reportedProviderReference").value("PG-REF-9"))
            .andExpect(jsonPath("$.data[0].openedAt").value("2026-10-20T00:30:00Z"))
            .andExpect(jsonPath("$.data[1].caseId").value(OTHER_CASE_ID))
            .andExpect(jsonPath("$.data[1].reportedAmount").value(null))
            .andExpect(jsonPath("$.data[1].reportedCurrency").value(null))
            .andExpect(jsonPath("$.data[1].reportedProviderReference").value(null))
            .andDo(
                document(
                    "reconciliation-cases-list",
                    STORE_PATH,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data[]").type(JsonFieldType.ARRAY).description("그 매장에서 점주가 아직 해소하지 않은(MANUAL_REVIEW) case. 최신순(caseId 내림차순)이고 최대 100건이며 페이지가 없다. 없으면 빈 배열"),
                        fieldWithPath("data[].caseId").type(JsonFieldType.STRING).description("case ID(UUID). 해소 요청의 경로에 쓴다"),
                        fieldWithPath("data[].paymentId").type(JsonFieldType.STRING).description("넘어온 결제 시도 ID(UUID)"),
                        fieldWithPath("data[].orderId").type(JsonFieldType.STRING).description("주문 ID(UUID)"),
                        fieldWithPath("data[].terminalId").type(JsonFieldType.STRING).description("결제를 요청한 단말 ID(UUID)"),
                        fieldWithPath("data[].checkoutNo").type(JsonFieldType.NUMBER).description("주문의 결제 요청 회차"),
                        fieldWithPath("data[].reason").type(JsonFieldType.STRING).description("case로 넘어온 사유. QUERY_LIMIT_EXCEEDED(조회 상한까지 결과 불명) 또는 AMOUNT_MISMATCH(provider가 다른 금액으로 승인했다고 답함)"),
                        fieldWithPath("data[].unknownReason").type(JsonFieldType.STRING).description("시도가 UNKNOWN이 된 사유. RESULT_UNCONFIRMED 또는 AMOUNT_MISMATCH"),
                        fieldWithPath("data[].status").type(JsonFieldType.STRING).description("case 상태. 이 목록에서는 항상 MANUAL_REVIEW"),
                        fieldWithPath("data[].ruleVersion").type(JsonFieldType.NUMBER).description("case를 분류한 규칙 버전"),
                        fieldWithPath("data[].amount").type(JsonFieldType.STRING).description("결제 시도 금액. 통화 기준 decimal string. 승인으로 해소할 때 approvedAmount가 이 값과 같아야 한다"),
                        fieldWithPath("data[].currency").type(JsonFieldType.STRING).description("시도 금액의 통화 코드(ISO 4217)"),
                        fieldWithPath("data[].queryCount").type(JsonFieldType.NUMBER).description("payment가 provider에 조회한 횟수"),
                        fieldWithPath("data[].reportedAmount").type(JsonFieldType.STRING).optional().description("조회가 알려 준 승인 금액. 통화 기준 decimal string. 알려 준 것이 없으면 null"),
                        fieldWithPath("data[].reportedCurrency").type(JsonFieldType.STRING).optional().description("조회가 알려 준 승인 금액의 통화 코드. reportedAmount가 null이면 null"),
                        fieldWithPath("data[].reportedProviderReference").type(JsonFieldType.STRING).optional().description("조회가 알려 준 PG 승인 참조. 없으면 null"),
                        fieldWithPath("data[].openedAt").type(JsonFieldType.STRING).description("payment가 넘긴 시각(ISO-8601 UTC)"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(stores).requireOwned(StoreId(UUID.fromString(STORE_ID)), OwnerId(UUID.fromString(OWNER_ID)))
    }

    @Test
    fun `승인으로 해소하면 200과 결과를 돌려주고 금액과 통화를 Money로 옮겨 커맨드로 부른다`() {
        given(management.resolve(APPROVE)).willReturn(
            ReconciliationCommand.Resolve.Result(
                ReconciliationCaseId(UUID.fromString(CASE_ID)), ReconciliationCaseStatus.RESOLVED, ReconciliationDecision.APPROVED,
                PaymentId(UUID.fromString(PAYMENT_ID)), PaymentAttemptStatus.APPROVED,
            ),
        )

        resolve(approveBody())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.caseId").value(CASE_ID))
            .andExpect(jsonPath("$.data.status").value("RESOLVED"))
            .andExpect(jsonPath("$.data.decision").value("APPROVED"))
            .andExpect(jsonPath("$.data.paymentId").value(PAYMENT_ID))
            .andExpect(jsonPath("$.data.paymentStatus").value("APPROVED"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "reconciliation-cases-resolve", CSRF_HEADER, CASE_PATH,
                    requestFields(
                        fieldWithPath("decision").type(JsonFieldType.STRING).description("점주가 PG 가맹점 화면에서 확인한 결론. APPROVED 또는 DECLINED"),
                        fieldWithPath("providerReference").type(JsonFieldType.STRING).optional().description("PG 승인 참조. 승인(APPROVED)에는 필수이고 255자 이하"),
                        fieldWithPath("approvedAmount").type(JsonFieldType.STRING).optional().description("PG 화면에서 본 승인 금액. 통화 기준 decimal string. 승인에는 필수이며 case의 시도 금액과 통화까지 같아야 한다. 거절에는 싣지 않는다. approvedCurrency와 함께만 온다"),
                        fieldWithPath("approvedCurrency").type(JsonFieldType.STRING).optional().description("승인 금액의 통화 코드(ISO 4217). approvedAmount와 함께만 온다"),
                        fieldWithPath("note").type(JsonFieldType.STRING).description("해소 사유. 비어 있지 않고 500자 이하. 해소 기록에 남고 로그에는 남지 않는다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.caseId").type(JsonFieldType.STRING).description("case ID(UUID)"),
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("해소 뒤 case 상태. 항상 RESOLVED. 같은 결론의 재요청도 같은 응답이다(멱등)"),
                        fieldWithPath("data.decision").type(JsonFieldType.STRING).description("적용된 결론 APPROVED 또는 DECLINED"),
                        fieldWithPath("data.paymentId").type(JsonFieldType.STRING).description("결론을 적용한 결제 시도 ID(UUID)"),
                        fieldWithPath("data.paymentStatus").type(JsonFieldType.STRING).description("결론을 적용한 뒤 결제 시도 상태. 승인이면 APPROVED, 거절이면 DECLINED"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(stores).requireOwned(StoreId(UUID.fromString(STORE_ID)), OwnerId(UUID.fromString(OWNER_ID)))
        verify(management).resolve(APPROVE)
    }

    @Test
    fun `거절로 해소하면 금액 없이 커맨드로 옮기고 사유가 없으면 빈 사유로 도메인이 거절하게 넘긴다`() {
        val decline = APPROVE.copy(decision = ReconciliationDecision.DECLINED, providerReference = null, approvedAmount = null)
        given(management.resolve(decline)).willReturn(
            ReconciliationCommand.Resolve.Result(
                ReconciliationCaseId(UUID.fromString(CASE_ID)), ReconciliationCaseStatus.RESOLVED, ReconciliationDecision.DECLINED,
                PaymentId(UUID.fromString(PAYMENT_ID)), PaymentAttemptStatus.DECLINED,
            ),
        )

        resolve("""{ "decision": "DECLINED", "note": "$NOTE" }""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.decision").value("DECLINED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("DECLINED"))

        val blank = decline.copy(note = "")
        given(management.resolve(blank)).willThrow(domainError(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID))
        resolve("""{ "decision": "DECLINED" }""").andExpect(status().isBadRequest)
        verify(management).resolve(blank)
    }

    @Test
    fun `승인 금액과 통화 중 하나만 오면 400 INVALID_RECONCILIATION_RESOLUTION이고 Facade를 부르지 않는다`() {
        resolve("""{ "decision": "APPROVED", "providerReference": "$PROVIDER_REF", "approvedAmount": "4500", "note": "$NOTE" }""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_RECONCILIATION_RESOLUTION"))
        resolve("""{ "decision": "APPROVED", "providerReference": "$PROVIDER_REF", "approvedCurrency": "KRW", "note": "$NOTE" }""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_RECONCILIATION_RESOLUTION"))

        verifyNoInteractions(management)
    }

    @Test
    fun `승인에 참조가 없거나 사유가 잘못되면 400 INVALID_RECONCILIATION_RESOLUTION이다`() {
        givenResolveFails(domainError(ReconciliationCase.INVALID_RECONCILIATION_RESOLUTION, DomainErrorKind.INVALID))

        resolve(approveBody())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_RECONCILIATION_RESOLUTION"))
            .andDo(document("reconciliation-cases-resolve-invalid", CSRF_HEADER, CASE_PATH, responseFields(failFields("INVALID_RECONCILIATION_RESOLUTION. 승인에 PG 승인 참조나 승인 금액이 없거나, 거절에 승인 금액이 있거나, 금액 불일치 case의 거절에 PG 취소 참조가 없거나, 사유가 비었거나 500자를 넘거나 사유·참조에 NUL 문자가 있다. 승인 금액과 통화 중 하나만 보낸 경우도 같다"))))
    }

    @Test
    fun `승인 금액이 시도 금액과 다르면 422 RECONCILIATION_AMOUNT_MISMATCH다`() {
        givenResolveFails(domainError(ReconciliationCase.RECONCILIATION_AMOUNT_MISMATCH, DomainErrorKind.UNPROCESSABLE))

        resolve(approveBody())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.errorCode").value("RECONCILIATION_AMOUNT_MISMATCH"))
            .andDo(document("reconciliation-cases-resolve-amount-mismatch", CSRF_HEADER, CASE_PATH, responseFields(failFields("RECONCILIATION_AMOUNT_MISMATCH. 승인 금액이 case의 시도 금액과 통화까지 같지 않다. 아무것도 바뀌지 않는다"))))
    }

    @Test
    fun `case가 없거나 다른 매장의 것이면 404 RECONCILIATION_CASE_NOT_FOUND다`() {
        givenResolveFails(ReconciliationCase.notFound())

        resolve(approveBody())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("RECONCILIATION_CASE_NOT_FOUND"))
            .andDo(document("reconciliation-cases-resolve-not-found", CSRF_HEADER, CASE_PATH, responseFields(failFields("RECONCILIATION_CASE_NOT_FOUND. case가 없거나 이 매장의 것이 아니다. 둘을 구분하지 않는다"))))
    }

    @Test
    fun `이미 다른 결론으로 해소된 case는 409 RECONCILIATION_CASE_ALREADY_RESOLVED다`() {
        givenResolveFails(domainError(ReconciliationCase.RECONCILIATION_CASE_ALREADY_RESOLVED, DomainErrorKind.CONFLICT))

        resolve(approveBody())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.errorCode").value("RECONCILIATION_CASE_ALREADY_RESOLVED"))
            .andDo(document("reconciliation-cases-resolve-already-resolved", CSRF_HEADER, CASE_PATH, responseFields(failFields("RECONCILIATION_CASE_ALREADY_RESOLVED. 이미 다른 결론으로 해소됐다. 같은 결론의 재요청은 200이다"))))
    }

    @Test
    fun `결제 시도가 이미 해소 결론과 다른 결말이면 409 RECONCILIATION_PAYMENT_ALREADY_CONCLUDED다`() {
        givenResolveFails(domainError(ReconciliationCase.RECONCILIATION_PAYMENT_ALREADY_CONCLUDED, DomainErrorKind.CONFLICT))

        resolve(approveBody())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.errorCode").value("RECONCILIATION_PAYMENT_ALREADY_CONCLUDED"))
            .andDo(document("reconciliation-cases-resolve-payment-concluded", CSRF_HEADER, CASE_PATH, responseFields(failFields("RECONCILIATION_PAYMENT_ALREADY_CONCLUDED. 결제 시도가 이미 요청한 결론과 다른 결말이다. case는 해소되지 않은 채이므로 결제 결말과 같은 결론으로 다시 해소한다"))))
    }

    @Test
    fun `남의 매장이거나 없는 매장이면 404 STORE_NOT_FOUND이고 case Facade를 부르지 않는다`() {
        willThrow(DomainException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))
            .given(stores).requireOwned(StoreId(UUID.fromString(STORE_ID)), OwnerId(UUID.fromString(OWNER_ID)))

        resolve(approveBody())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("STORE_NOT_FOUND"))
            .andDo(document("reconciliation-cases-store-not-found", CSRF_HEADER, CASE_PATH, responseFields(failFields("STORE_NOT_FOUND. 매장이 없거나 로그인한 점주의 것이 아니다. 둘을 구분하지 않고 모든 case endpoint가 같다"))))
        list()
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("STORE_NOT_FOUND"))

        verifyNoInteractions(management, queryFacade)
    }

    @Test
    fun `로그인하지 않으면 401 UNAUTHENTICATED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(get("/owner/v1/stores/{storeId}/reconciliation-cases", STORE_ID))
            .andExpect(status().isUnauthorized)
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
            .andDo(document("reconciliation-cases-unauthenticated", responseFields(failFields("UNAUTHENTICATED. 세션 쿠키가 없거나 만료됐거나 로그아웃했다. 원인을 나누지 않는다"))))

        verifyNoInteractions(queryFacade, management, stores)
    }

    @Test
    fun `CSRF 토큰이 없는 해소 요청은 403 ACCESS_DENIED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(
            post("/owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve", STORE_ID, CASE_ID)
                .with(owner).contentType(MediaType.APPLICATION_JSON).content(approveBody()),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.errorCode").value("ACCESS_DENIED"))
            .andDo(document("reconciliation-cases-access-denied", responseFields(failFields("ACCESS_DENIED. 상태 변경 요청에 `X-XSRF-TOKEN` 헤더가 없거나 `__Host-XSRF-TOKEN` 쿠키와 다르다"))))

        verifyNoInteractions(management, stores)
    }

    companion object {
        private const val OWNER_ID = "018f6b1a-0000-7000-8000-0000000000b1"
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val CASE_ID = "018f6b1a-0000-7000-8000-0000000000c2"
        private const val OTHER_CASE_ID = "018f6b1a-0000-7000-8000-0000000000c1"
        private const val PAYMENT_ID = "018f6b1a-0000-7000-8000-0000000000d1"
        private const val ORDER_ID = "018f6b1a-0000-7000-8000-0000000000e1"
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000f1"
        private const val PROVIDER_REF = "PG-REF-1"
        private const val NOTE = "PG 가맹점 화면에서 승인 확인"
        private val KRW: Currency = Currency.getInstance("KRW")

        private val APPROVE = ReconciliationCommand.Resolve(
            caseId = ReconciliationCaseId(UUID.fromString(CASE_ID)),
            storeId = StoreId(UUID.fromString(STORE_ID)),
            ownerId = OwnerId(UUID.fromString(OWNER_ID)),
            decision = ReconciliationDecision.APPROVED,
            providerReference = PROVIDER_REF,
            approvedAmount = Money.of("4500", KRW),
            note = NOTE,
        )

        private fun view(caseId: String, reason: PaymentReconciliationReason, reported: Money? = null, reportedRef: String? = null) = ReconciliationCaseView(
            caseId = ReconciliationCaseId(UUID.fromString(caseId)),
            paymentId = PaymentId(UUID.fromString(PAYMENT_ID)),
            orderId = UUID.fromString(ORDER_ID),
            terminalId = TerminalId(UUID.fromString(TERMINAL_ID)),
            checkoutNo = 1,
            reason = reason,
            unknownReason = PaymentUnknownReason.RESULT_UNCONFIRMED,
            status = ReconciliationCaseStatus.MANUAL_REVIEW,
            ruleVersion = 1,
            amount = Money.of("4500", KRW),
            queryCount = 3,
            reportedAmount = reported,
            reportedProviderReference = reportedRef,
            openedAt = Instant.parse("2026-10-20T00:30:00Z"),
        )

        private val CSRF_HEADER = requestHeaders(
            headerWithName("X-XSRF-TOKEN").description("`GET /owner/v1/csrf`가 심은 `__Host-XSRF-TOKEN` 쿠키의 값. 없거나 쿠키와 다르면 403 ACCESS_DENIED. 세션 쿠키가 없거나 만료되면 401 UNAUTHENTICATED"),
        )
        private val STORE_PARAMETER = parameterWithName("storeId").description("매장 ID(UUID). 로그인한 점주의 매장이 아니거나 없으면 404 STORE_NOT_FOUND")
        private val STORE_PATH = pathParameters(STORE_PARAMETER)
        private val CASE_PATH = pathParameters(STORE_PARAMETER, parameterWithName("caseId").description("해소할 case ID(UUID). 그 매장의 case가 아니면 404 RECONCILIATION_CASE_NOT_FOUND"))

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
