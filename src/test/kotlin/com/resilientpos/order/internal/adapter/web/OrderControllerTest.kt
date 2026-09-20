package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.CreateOrderCommand
import com.resilientpos.order.CreateOrderLine
import com.resilientpos.order.CreateOrderResult
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

/**
 * `POST /v1/orders` 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·오류 변환만 검사한다.
 * 문서화하는 사례는 assertion으로 먼저 검증한 같은 요청·응답으로 스니펫을 만든다.
 */
@WebMvcTest(OrderController::class)
@AutoConfigureRestDocs
class OrderControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var facade: OrderCommandFacade

    @Test
    fun `유효한 요청은 본문을 CreateOrderCommand로 옮기고 201과 orderId·status를 돌려준다`() {
        val orderId = OrderId(UUID.fromString("018f6b1a-0000-7000-8000-000000000001"))
        given(facade.create(command())).willReturn(CreateOrderResult(orderId, OrderStatus.OPEN))

        postOrder(requestJson()).andExpect {
            status { isCreated() }
            jsonPath("$.result") { value("SUCCESS") }
            jsonPath("$.data.orderId") { value(orderId.value.toString()) }
            jsonPath("$.data.status") { value("OPEN") }
            jsonPath("$.error") { value(null) }
        }.andDo {
            handle(
                document(
                    "orders-create",
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.orderId").type(JsonFieldType.STRING).description("생성된 주문 ID"),
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("주문 상태"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
        }

        // Mockito matcher는 Kotlin non-null 파라미터에 null을 넘겨 NPE가 나므로 data class 동등성으로 검증한다.
        verify(facade).create(command())
    }

    @Test
    fun `도메인이 거절하면 예외의 code와 message를 담은 400이다`() {
        given(facade.create(command(lines = emptyList())))
            .willThrow(DomainException("EMPTY_ORDER", "주문에는 line이 1개 이상 필요합니다"))

        postOrder(requestJson(lines = "")).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("EMPTY_ORDER") }
            jsonPath("$.error.message") { value("주문에는 line이 1개 이상 필요합니다") }
        }.andDo {
            handle(
                document(
                    "orders-create-domain-rejected",
                    requestFields(
                        HEADER_FIELDS + fieldWithPath("lines").type(JsonFieldType.ARRAY).description("주문 line 목록. 비어 있으면 EMPTY_ORDER"),
                    ),
                    responseFields(failFields("도메인 오류 코드")),
                ),
            )
        }
    }

    @Test
    fun `unitPrice가 통화 자릿수를 넘으면 Facade를 부르기 전에 400 INVALID_MONEY_SCALE이다`() {
        postOrder(requestJson(unitPrice = "1000.5")).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("INVALID_MONEY_SCALE") }
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `terminalId가 UUID 형식이 아니면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        expectBadRequest(requestJson(terminalId = "not-a-uuid")).andDo {
            handle(
                document(
                    "orders-create-bad-request",
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(failFields("BAD_REQUEST 고정 코드")),
                ),
            )
        }
    }

    // "XXX"는 ISO 4217이 "통화 없음"으로 정의한 실제 코드라서 거절되지 않는다.
    @Test
    fun `없는 통화 코드는 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        expectBadRequest(requestJson(currency = "ZZZ"))
    }

    @Test
    fun `storeId가 빠지면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        expectBadRequest(requestJson().replace(Regex("\"storeId\": \"[^\"]+\","), ""))
    }

    @Test
    fun `깨진 JSON은 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        expectBadRequest("{ broken")
    }

    private fun postOrder(body: String): ResultActionsDsl = mockMvc.post("/v1/orders") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun expectBadRequest(body: String): ResultActionsDsl {
        val result = postOrder(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("BAD_REQUEST") }
        }
        verifyNoInteractions(facade)
        return result
    }

    companion object {
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private const val CLIENT_ORDER_ID = "018f6b1a-0000-7000-8000-0000000000a3"
        private val krw: Currency = Currency.getInstance("KRW")

        private fun command(
            lines: List<CreateOrderLine> = listOf(CreateOrderLine("SKU-1", "아메리카노", Money.of("4500", krw), 2)),
        ) = CreateOrderCommand(
            storeId = StoreId(UUID.fromString(STORE_ID)),
            terminalId = TerminalId(UUID.fromString(TERMINAL_ID)),
            clientOrderId = ClientOrderId(UUID.fromString(CLIENT_ORDER_ID)),
            currency = krw,
            lines = lines,
        )

        private fun requestJson(
            terminalId: String = TERMINAL_ID,
            currency: String = "KRW",
            unitPrice: String = "4500",
            lines: String = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "$unitPrice", "quantity": 2 }""",
        ) = """
            {
              "storeId": "$STORE_ID",
              "terminalId": "$terminalId",
              "clientOrderId": "$CLIENT_ORDER_ID",
              "currency": "$currency",
              "lines": [$lines]
            }
        """.trimIndent()

        private val HEADER_FIELDS: List<FieldDescriptor> = listOf(
            fieldWithPath("storeId").type(JsonFieldType.STRING).description("매장 ID(UUID)"),
            fieldWithPath("terminalId").type(JsonFieldType.STRING).description("단말 ID(UUID)"),
            fieldWithPath("clientOrderId").type(JsonFieldType.STRING).description("단말이 부여한 주문 생성 요청 ID(UUID). 재시도 판별 키"),
            fieldWithPath("currency").type(JsonFieldType.STRING).description("ISO 4217 통화 코드"),
        )

        private val LINE_FIELDS: List<FieldDescriptor> = listOf(
            fieldWithPath("lines[].productCode").type(JsonFieldType.STRING).description("상품 코드"),
            fieldWithPath("lines[].productName").type(JsonFieldType.STRING).description("상품명"),
            fieldWithPath("lines[].unitPrice").type(JsonFieldType.STRING).description("단가. 주문 통화 기준 decimal string"),
            fieldWithPath("lines[].quantity").type(JsonFieldType.NUMBER).description("수량"),
        )

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
