package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderDetailView
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderQueryFacade
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.MockTerminalRateLimiter
import com.resilientpos.terminal.TerminalFixtures.AUTHORIZATION_HEADER
import com.resilientpos.terminal.TerminalFixtures.BEARER
import com.resilientpos.terminal.TerminalFixtures.TOKEN
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import java.time.Instant
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
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
 * `GET /v1/orders/{orderId}` 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·인증·오류 변환만 검사한다.
 * 경로 변수를 문서화하려면 URL 템플릿이 필요해서 Kotlin DSL 대신 REST Docs의 request builder를 쓴다.
 */
@MockTerminalRateLimiter
@WebMvcTest(OrderController::class)
@AutoConfigureRestDocs
@MockitoBean(types = [OrderCommandFacade::class, OrderQueryFacade::class, TerminalManagement::class])
class OrderGetControllerTest(
    private val mockMvc: MockMvc,
    private val queryFacade: OrderQueryFacade,
    private val terminals: TerminalManagement,
) {

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(AuthenticatedTerminal(TerminalId(UUID.fromString(TERMINAL_ID)), StoreId(UUID.fromString(STORE_ID))))
    }

    private fun getOrder(orderId: String, authorization: String? = BEARER): ResultActions =
        mockMvc.perform(get("/v1/orders/{orderId}", orderId).apply { authorization?.let { header("Authorization", it) } })

    @Test
    fun `인증 단말의 매장으로 조회하고 200과 주문 필드와 line과 checkout을 돌려준다`() {
        given(queryFacade.getOrder(QUERY)).willReturn(detail(OrderStatus.CHECKOUT_REQUESTED, version = 2, checkouts = listOf(OrderDetailView.Checkout(1, Money.of("3502", krw)))))

        getOrder(ORDER_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
            .andExpect(jsonPath("$.data.clientOrderId").value(CLIENT_ORDER_ID))
            .andExpect(jsonPath("$.data.terminalId").value(TERMINAL_ID))
            .andExpect(jsonPath("$.data.status").value("CHECKOUT_REQUESTED"))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.currency").value("KRW"))
            .andExpect(jsonPath("$.data.total").value("3502"))
            .andExpect(jsonPath("$.data.occurredAt").value("2026-10-26T01:02:03Z"))
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[0].productCode").value("SKU-1"))
            .andExpect(jsonPath("$.data.lines[0].productName").value("아메리카노"))
            .andExpect(jsonPath("$.data.lines[0].unitPrice").value("1001"))
            .andExpect(jsonPath("$.data.lines[0].quantity").value(2))
            .andExpect(jsonPath("$.data.lines[1].productCode").value("SKU-2"))
            .andExpect(jsonPath("$.data.lines[1].unitPrice").value("500"))
            .andExpect(jsonPath("$.data.lines[1].quantity").value(3))
            .andExpect(jsonPath("$.data.checkouts.length()").value(1))
            .andExpect(jsonPath("$.data.checkouts[0].checkoutNo").value(1))
            .andExpect(jsonPath("$.data.checkouts[0].amount").value("3502"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "orders-get",
                    AUTHORIZATION_HEADER, ORDER_PATH_PARAMETERS,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.orderId").type(JsonFieldType.STRING).description("주문 ID(UUID)"),
                        fieldWithPath("data.clientOrderId").type(JsonFieldType.STRING).description("단말이 주문 생성 때 보낸 요청 식별자(UUID)"),
                        fieldWithPath("data.terminalId").type(JsonFieldType.STRING).description("주문을 만든 단말 ID(UUID)"),
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("OPEN, CHECKOUT_REQUESTED, PAID"),
                        fieldWithPath("data.version").type(JsonFieldType.NUMBER).description("주문의 aggregate version. 단말이 주문이 바뀌었는지 비교하는 값이다"),
                        fieldWithPath("data.currency").type(JsonFieldType.STRING).description("주문 통화(ISO 4217)"),
                        fieldWithPath("data.total").type(JsonFieldType.STRING).description("line 단가 곱하기 수량의 합계. 통화 기준 decimal string"),
                        fieldWithPath("data.occurredAt").type(JsonFieldType.STRING).description("주문이 팔린 시각(ISO-8601 UTC). 서버가 받은 시각이 아니다"),
                        fieldWithPath("data.lines[]").type(JsonFieldType.ARRAY).description("주문 line"),
                        fieldWithPath("data.lines[].productCode").type(JsonFieldType.STRING).description("상품 코드"),
                        fieldWithPath("data.lines[].productName").type(JsonFieldType.STRING).description("상품 이름"),
                        fieldWithPath("data.lines[].unitPrice").type(JsonFieldType.STRING).description("단가. 통화 기준 decimal string"),
                        fieldWithPath("data.lines[].quantity").type(JsonFieldType.NUMBER).description("수량"),
                        fieldWithPath("data.checkouts[]").type(JsonFieldType.ARRAY).description("checkout 회차별 확정 금액. checkout 전 OPEN 주문은 빈 배열"),
                        fieldWithPath("data.checkouts[].checkoutNo").type(JsonFieldType.NUMBER).description("checkout 회차"),
                        fieldWithPath("data.checkouts[].amount").type(JsonFieldType.STRING).description("그 회차의 확정 금액. 통화 기준 decimal string"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(queryFacade).getOrder(QUERY)
    }

    @Test
    fun `checkout 전 OPEN 주문은 200과 빈 checkouts다`() {
        given(queryFacade.getOrder(QUERY)).willReturn(detail(OrderStatus.OPEN, version = 0, checkouts = emptyList()))

        getOrder(ORDER_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.checkouts").isArray)
            .andExpect(jsonPath("$.data.checkouts.length()").value(0))
    }

    @Test
    fun `인증 단말의 매장이 조회에 실린다`() {
        val otherStore = StoreId(UUID.fromString(OTHER_STORE_ID))
        given(terminals.authenticate(TOKEN)).willReturn(AuthenticatedTerminal(TerminalId(UUID.fromString(TERMINAL_ID)), otherStore))
        given(queryFacade.getOrder(OrderQuery.GetOrder(OrderId(UUID.fromString(ORDER_ID)), otherStore))).willReturn(detail(OrderStatus.OPEN, version = 0, checkouts = emptyList()))

        getOrder(ORDER_ID).andExpect(status().isOk)

        verify(queryFacade).getOrder(OrderQuery.GetOrder(OrderId(UUID.fromString(ORDER_ID)), otherStore))
    }

    @Test
    fun `주문이 없거나 다른 매장의 것이면 404 ORDER_NOT_FOUND다`() {
        given(queryFacade.getOrder(QUERY)).willThrow(DomainException("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        getOrder(ORDER_ID)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("ORDER_NOT_FOUND"))
            .andDo(document("orders-get-not-found", AUTHORIZATION_HEADER, ORDER_PATH_PARAMETERS, responseFields(failFields("ORDER_NOT_FOUND. 없는 주문과 다른 매장의 주문을 구분하지 않는다"))))
    }

    @Test
    fun `Authorization 헤더가 없으면 401 UNAUTHENTICATED와 WWW-Authenticate이고 Facade를 부르지 않는다`() {
        getOrder(ORDER_ID, authorization = null)
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))

        verifyNoInteractions(queryFacade)
    }

    @Test
    fun `orderId가 UUID 형식이 아니면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        getOrder("not-a-uuid")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
            .andDo(document("orders-get-bad-request", AUTHORIZATION_HEADER, ORDER_PATH_PARAMETERS, responseFields(failFields("BAD_REQUEST 고정 코드"))))

        verifyNoInteractions(queryFacade)
    }

    companion object {
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val OTHER_STORE_ID = "018f6b1a-0000-7000-8000-0000000000a9"
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private const val ORDER_ID = "018f6b1a-0000-7000-8000-0000000000c1"
        private const val CLIENT_ORDER_ID = "018f6b1a-0000-7000-8000-0000000000c2"
        private val QUERY = OrderQuery.GetOrder(OrderId(UUID.fromString(ORDER_ID)), StoreId(UUID.fromString(STORE_ID)))
        private val krw: Currency = Currency.getInstance("KRW")

        private fun detail(status: OrderStatus, version: Long, checkouts: List<OrderDetailView.Checkout>) = OrderDetailView(
            orderId = OrderId(UUID.fromString(ORDER_ID)),
            clientOrderId = ClientOrderId(UUID.fromString(CLIENT_ORDER_ID)),
            terminalId = TerminalId(UUID.fromString(TERMINAL_ID)),
            status = status,
            version = version,
            currency = krw,
            total = Money.of("3502", krw),
            occurredAt = Instant.parse("2026-10-26T01:02:03Z"),
            lines = listOf(
                OrderDetailView.Line("SKU-1", "아메리카노", Money.of("1001", krw), 2),
                OrderDetailView.Line("SKU-2", "카페라떼", Money.of("500", krw), 3),
            ),
            checkouts = checkouts,
        )

        private val ORDER_PATH_PARAMETERS = pathParameters(parameterWithName("orderId").description("조회할 주문 ID(UUID). 인증 단말의 매장 주문이 아니면 404 ORDER_NOT_FOUND"))

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
