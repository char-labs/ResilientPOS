package com.resilientpos.payment.internal.adapter.web

import com.resilientpos.contract.PaymentAttemptKind
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentAttemptView
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.PaymentQuery
import com.resilientpos.payment.PaymentQueryFacade
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.MockTerminalRateLimiter
import com.resilientpos.terminal.TerminalFixtures.AUTHORIZATION_HEADER
import com.resilientpos.terminal.TerminalFixtures.BEARER
import com.resilientpos.terminal.TerminalFixtures.TOKEN
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.CannotAcquireLockException
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 결제 시도 조회·재결제 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·인증·오류 변환만 검사한다.
 * 경로 변수를 문서화하려면 URL 템플릿이 필요해서 Kotlin DSL 대신 REST Docs의 request builder를 쓴다.
 */
@MockTerminalRateLimiter
@WebMvcTest(PaymentAttemptController::class)
@AutoConfigureRestDocs
class PaymentAttemptControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var queryFacade: PaymentQueryFacade

    @MockitoBean
    private lateinit var management: PaymentManagement

    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(AuthenticatedTerminal(TerminalId(UUID.fromString(TERMINAL_ID)), StoreId(UUID.fromString(STORE_ID))))
    }

    private fun findAttempts(orderId: String, authorization: String? = BEARER): ResultActions =
        mockMvc.perform(get("/v1/orders/{orderId}/payment-attempts", orderId).apply { authorization?.let { header("Authorization", it) } })

    private fun retry(paymentId: String, authorization: String? = BEARER): ResultActions =
        mockMvc.perform(post("/v1/payment-attempts/{paymentId}/retry", paymentId).apply { authorization?.let { header("Authorization", it) } })

    @Test
    fun `인증 단말의 매장으로 시도를 조회하고 200과 항목 필드를 돌려준다`() {
        given(queryFacade.findAttempts(QUERY)).willReturn(
            listOf(
                PaymentAttemptView(PaymentId(UUID.fromString(DECLINED_ID)), 1, PaymentAttemptKind.AUTHORIZATION, PaymentAttemptStatus.DECLINED, Money.of("9001", krw)),
                PaymentAttemptView(PaymentId(UUID.fromString(RETRIED_ID)), 1, PaymentAttemptKind.AUTHORIZATION, PaymentAttemptStatus.REQUESTED, Money.of("30.30", usd)),
            ),
        )

        findAttempts(ORDER_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].paymentId").value(DECLINED_ID))
            .andExpect(jsonPath("$.data[0].checkoutNo").value(1))
            .andExpect(jsonPath("$.data[0].kind").value("AUTHORIZATION"))
            .andExpect(jsonPath("$.data[0].status").value("DECLINED"))
            .andExpect(jsonPath("$.data[0].amount").value("9001"))
            .andExpect(jsonPath("$.data[0].currency").value("KRW"))
            .andExpect(jsonPath("$.data[1].paymentId").value(RETRIED_ID))
            .andExpect(jsonPath("$.data[1].status").value("REQUESTED"))
            .andExpect(jsonPath("$.data[1].amount").value("30.30"))
            .andExpect(jsonPath("$.data[1].currency").value("USD"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "payment-attempts-list",
                    AUTHORIZATION_HEADER, ORDER_PATH_PARAMETERS,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data[]").type(JsonFieldType.ARRAY).description("그 주문의 결제 시도. 생성 순이다"),
                        fieldWithPath("data[].paymentId").type(JsonFieldType.STRING).description("결제 시도 ID(UUID). 재결제는 이 값으로 요청한다"),
                        fieldWithPath("data[].checkoutNo").type(JsonFieldType.NUMBER).description("checkout 회차"),
                        fieldWithPath("data[].kind").type(JsonFieldType.STRING).description("AUTHORIZATION 또는 CANCELLATION"),
                        fieldWithPath("data[].status").type(JsonFieldType.STRING).description("REQUESTED, APPROVED, DECLINED, UNKNOWN. UNKNOWN이면 기다린다"),
                        fieldWithPath("data[].amount").type(JsonFieldType.STRING).description("시도 금액. 통화 기준 decimal string"),
                        fieldWithPath("data[].currency").type(JsonFieldType.STRING).description("ISO 4217 통화 코드"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(queryFacade).findAttempts(QUERY)
    }

    @Test
    fun `조회 결과가 비면 200과 빈 배열이다`() {
        given(queryFacade.findAttempts(QUERY)).willReturn(emptyList())

        findAttempts(ORDER_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
            .andDo(
                document(
                    "payment-attempts-list-empty",
                    AUTHORIZATION_HEADER, ORDER_PATH_PARAMETERS,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data").type(JsonFieldType.ARRAY).description("빈 배열. 주문이 없거나 다른 매장의 주문이거나 시도가 아직 없을 때 모두 같다"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
    }

    @Test
    fun `경로의 paymentId와 인증 단말의 매장을 재결제 커맨드로 옮기고 200과 새 시도를 돌려준다`() {
        given(management.retryAuthorization(RETRY)).willReturn(PaymentCommand.RetryAuthorization.Result(PaymentId(UUID.fromString(RETRIED_ID)), PaymentAttemptStatus.REQUESTED))

        retry(DECLINED_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.paymentId").value(RETRIED_ID))
            .andExpect(jsonPath("$.data.status").value("REQUESTED"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "payment-attempts-retry",
                    AUTHORIZATION_HEADER, PAYMENT_PATH_PARAMETERS,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.paymentId").type(JsonFieldType.STRING).description("새 시도의 ID(UUID). 이미 재결제했으면 그 회차의 최신 시도"),
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("그 시도의 현재 상태. 새 시도면 REQUESTED이고 결말은 조회 API로 본다"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(management).retryAuthorization(RETRY)
    }

    @Test
    fun `재결제 대상이 없거나 다른 매장의 시도면 404 PAYMENT_ATTEMPT_NOT_FOUND다`() {
        given(management.retryAuthorization(RETRY))
            .willThrow(DomainException("PAYMENT_ATTEMPT_NOT_FOUND", "결제 시도를 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        retry(DECLINED_ID)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("PAYMENT_ATTEMPT_NOT_FOUND"))
            .andDo(document("payment-attempts-retry-not-found", AUTHORIZATION_HEADER, PAYMENT_PATH_PARAMETERS, responseFields(failFields("PAYMENT_ATTEMPT_NOT_FOUND. 없는 시도와 다른 매장의 시도를 구분하지 않는다"))))
    }

    @Test
    fun `재결제할 수 없는 시도면 422 PAYMENT_NOT_RETRYABLE이다`() {
        given(management.retryAuthorization(RETRY))
            .willThrow(DomainException("PAYMENT_NOT_RETRYABLE", "재결제할 수 없는 결제 시도입니다", DomainErrorKind.UNPROCESSABLE))

        retry(DECLINED_ID)
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("PAYMENT_NOT_RETRYABLE"))
            .andDo(document("payment-attempts-retry-not-retryable", AUTHORIZATION_HEADER, PAYMENT_PATH_PARAMETERS, responseFields(failFields("PAYMENT_NOT_RETRYABLE. 최신 시도가 DECLINED가 아니면(REQUESTED·UNKNOWN·APPROVED·취소) 재결제하지 않는다"))))
    }

    @Test
    fun `재결제의 잠금 대기가 초과되면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다`() {
        given(management.retryAuthorization(RETRY)).willThrow(CannotAcquireLockException("could not obtain lock on row in relation t_payment_attempt"))

        retry(DECLINED_ID)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("CONCURRENT_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("동시 처리 충돌로 요청을 완료하지 못했습니다."))
            .andDo(
                document(
                    "payment-attempts-retry-concurrent-request",
                    AUTHORIZATION_HEADER, PAYMENT_PATH_PARAMETERS,
                    responseFields(failFields("CONCURRENT_REQUEST 고정 코드. 상태로 멱등을 판정하므로 같은 요청을 다시 보내면 된다")),
                ),
            )
    }

    @Test
    fun `두 endpoint 모두 Authorization 헤더가 없으면 401 UNAUTHENTICATED와 WWW-Authenticate이고 Facade를 부르지 않는다`() {
        findAttempts(ORDER_ID, authorization = null)
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
        retry(DECLINED_ID, authorization = null)
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))

        verifyNoInteractions(queryFacade, management)
    }

    @Test
    fun `두 endpoint 모두 경로 식별자가 UUID 형식이 아니면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        findAttempts("not-a-uuid")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
            .andDo(document("payment-attempts-list-bad-request", AUTHORIZATION_HEADER, ORDER_PATH_PARAMETERS, responseFields(failFields("BAD_REQUEST 고정 코드"))))
        retry("not-a-uuid")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
            .andDo(document("payment-attempts-retry-bad-request", AUTHORIZATION_HEADER, PAYMENT_PATH_PARAMETERS, responseFields(failFields("BAD_REQUEST 고정 코드"))))

        verifyNoInteractions(queryFacade, management)
    }

    companion object {
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private const val ORDER_ID = "00000000-0000-0000-0000-0000000000a1"
        private const val DECLINED_ID = "018f6b1a-0000-7000-8000-0000000000b1"
        private const val RETRIED_ID = "018f6b1a-0000-7000-8000-0000000000b2"
        private val QUERY = PaymentQuery.FindAttempts(UUID.fromString(ORDER_ID), StoreId(UUID.fromString(STORE_ID)))
        private val RETRY = PaymentCommand.RetryAuthorization(PaymentId(UUID.fromString(DECLINED_ID)), StoreId(UUID.fromString(STORE_ID)))
        private val krw: Currency = Currency.getInstance("KRW")
        private val usd: Currency = Currency.getInstance("USD")

        private val ORDER_PATH_PARAMETERS = pathParameters(parameterWithName("orderId").description("결제 시도를 조회할 주문 ID(UUID)"))
        private val PAYMENT_PATH_PARAMETERS = pathParameters(parameterWithName("paymentId").description("다시 결제할 거절된 시도의 ID(UUID)"))

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
