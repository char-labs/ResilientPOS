package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
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
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("주문의 현재 상태. 생성 직후에는 OPEN이고 checkout을 요청한 주문의 재시도면 CHECKOUT_REQUESTED"),
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
    fun `Facade가 IDEMPOTENCY_CONFLICT를 던지면 409이고 도메인 오류 코드를 담는다`() {
        given(facade.create(command()))
            .willThrow(
                DomainException(
                    "IDEMPOTENCY_CONFLICT",
                    "같은 clientOrderId로 내용이 다른 주문 생성 요청이 이미 처리되었습니다",
                    DomainErrorKind.CONFLICT,
                ),
            )

        postOrder(requestJson()).andExpect {
            status { isEqualTo(409) }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("IDEMPOTENCY_CONFLICT") }
            jsonPath("$.error.message") { value("같은 clientOrderId로 내용이 다른 주문 생성 요청이 이미 처리되었습니다") }
        }.andDo {
            handle(
                document(
                    "orders-create-idempotency-conflict",
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
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
    fun `currency가 금액을 표현할 수 없는 통화면 400 UNSUPPORTED_CURRENCY이다`() {
        postOrder(requestJson(currency = "XAU")).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("UNSUPPORTED_CURRENCY") }
        }.andDo {
            handle(
                document(
                    "orders-create-unsupported-currency",
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(failFields("UNSUPPORTED_CURRENCY 고정 코드")),
                ),
            )
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `자릿수가 아주 많은 unitPrice는 본문을 읽는 단계에서 400으로 끊긴다`() {
        // Jackson의 숫자 길이 제한(StreamReadConstraints, 기본 1000자)이 여기서 끊어 준다는 사실에
        // Money의 판정 비용이 기대고 있다. 제한을 올리는 변경은 이 테스트를 깨뜨려 드러난다.
        postOrder(requestJson(unitPrice = "1" + "0".repeat(1_000))).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `unitPrice가 지수 표기로 저장 범위를 넘으면 400 INVALID_MONEY_AMOUNT이다`() {
        postOrder(requestJson(unitPrice = "1E+100000")).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.error.errorCode") { value("INVALID_MONEY_AMOUNT") }
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `상한을 넘는 본문은 읽는 단계에서 400으로 끊기고 Facade에 닿지 않는다`() {
        // max-document-length(1 MiB)를 넘긴다. productName 하나를 크게 키워 본문만 부풀린다.
        val huge = """{ "productCode": "SKU-1", "productName": "${"가".repeat(400_000)}", "unitPrice": "4500", "quantity": 1 }"""

        postOrder(requestJson(lines = huge)).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
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

    // "ZZZ"는 ISO 4217에 없는 코드라서 Currency 역직렬화 단계에서 끊긴다. "XXX"는 실재하는 코드라 도메인까지 가 UNSUPPORTED_CURRENCY가 된다.
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
            fieldWithPath("lines[].productCode").type(JsonFieldType.STRING).description("상품 코드. 비어 있거나 공백뿐이면 BLANK_PRODUCT_CODE"),
            fieldWithPath("lines[].productName").type(JsonFieldType.STRING).description("상품명. 비어 있거나 공백뿐이면 BLANK_PRODUCT_NAME"),
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
