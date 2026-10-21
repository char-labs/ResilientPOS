package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderStatus
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalFixtures.AUTHORIZATION_HEADER
import com.resilientpos.terminal.TerminalFixtures.BEARER
import com.resilientpos.terminal.TerminalFixtures.TOKEN
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.internal.adapter.redis.RedisTerminalRateLimiter
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
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

    @MockitoBean
    private lateinit var terminals: TerminalManagement

    @MockitoBean
    private lateinit var rateLimiter: RedisTerminalRateLimiter

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(AuthenticatedTerminal(TerminalId(UUID.fromString(TERMINAL_ID)), StoreId(UUID.fromString(STORE_ID))))
    }

    @Test
    fun `유효한 요청은 본문을 생성 커맨드로 옮기고 201과 orderId·status를 돌려준다`() {
        val orderId = OrderId(UUID.fromString("018f6b1a-0000-7000-8000-000000000001"))
        given(facade.create(command())).willReturn(OrderCommand.Create.Result(orderId, OrderStatus.OPEN))

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
                    AUTHORIZATION_HEADER,
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.orderId").type(JsonFieldType.STRING).description("생성된 주문 ID"),
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("주문의 현재 상태. 생성 직후에는 OPEN이고 checkout을 요청한 주문의 재시도면 CHECKOUT_REQUESTED, 결제가 승인된 뒤면 PAID"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
        }

        // Mockito matcher는 Kotlin non-null 파라미터에 null을 넘겨 NPE가 나므로 data class 동등성으로 검증한다.
        verify(facade).create(command())
    }

    @Test
    fun `Authorization 헤더가 없으면 401 UNAUTHENTICATED와 WWW-Authenticate이고 Facade를 부르지 않는다`() {
        postOrder(requestJson(), authorization = null).andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
            jsonPath("$.error.message") { value("인증이 필요합니다.") }
        }.andDo {
            handle(
                document(
                    "orders-create-unauthenticated",
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(failFields("UNAUTHENTICATED 고정 코드. 헤더 없음·형식 오류·모르는 토큰·회전된 토큰·비활성 단말이 모두 같다")),
                    responseHeaders(headerWithName("WWW-Authenticate").description("항상 Bearer")),
                ),
            )
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `Bearer 형식이 아니거나 authenticate가 없음을 돌려주면 같은 401이고 Facade를 부르지 않는다`() {
        for (authorization in listOf("Basic dGVzdDp0ZXN0", "Bearer", "Bearer  ", "Bearer unknown-token")) {
            postOrder(requestJson(), authorization = authorization).andExpect {
                status { isUnauthorized() }
                header { string("WWW-Authenticate", "Bearer") }
                jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
                jsonPath("$.error.message") { value("인증이 필요합니다.") }
            }
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `본문의 storeId나 terminalId가 인증 주체와 다르면 403 TERMINAL_MISMATCH이고 Facade를 부르지 않는다`() {
        postOrder(requestJson(terminalId = OTHER_ID)).andExpect {
            status { isForbidden() }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("TERMINAL_MISMATCH") }
        }.andDo {
            handle(
                document(
                    "orders-create-terminal-mismatch",
                    AUTHORIZATION_HEADER,
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(failFields("TERMINAL_MISMATCH 고정 코드. 본문의 storeId나 terminalId가 인증된 단말과 다르다")),
                ),
            )
        }
        postOrder(requestJson(storeId = OTHER_ID)).andExpect {
            status { isForbidden() }
            jsonPath("$.error.errorCode") { value("TERMINAL_MISMATCH") }
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `금액이 잘못된 본문도 소유권이 다르면 변환 오류 400보다 403 TERMINAL_MISMATCH가 먼저다`() {
        postOrder(requestJson(storeId = OTHER_ID, unitPrice = "4500.5")).andExpect {
            status { isForbidden() }
            jsonPath("$.error.errorCode") { value("TERMINAL_MISMATCH") }
        }

        verifyNoInteractions(facade)
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
                    AUTHORIZATION_HEADER,
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
                    AUTHORIZATION_HEADER,
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(failFields("도메인 오류 코드")),
                ),
            )
        }
    }

    @Test
    fun `Facade가 경합 예외를 던지면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다`() {
        given(facade.create(command()))
            .willThrow(
                ConcurrencyFailureException(
                    "같은 키의 주문이 동시에 저장됐다 terminalId=$TERMINAL_ID clientOrderId=$CLIENT_ORDER_ID",
                    DataIntegrityViolationException("duplicate key value violates unique constraint \"t_order_terminal_id_client_order_id_key\""),
                ),
            )

        postOrder(requestJson()).andExpect {
            status { isEqualTo(409) }
            jsonPath("$.result") { value("FAIL") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.error.errorCode") { value("CONCURRENT_REQUEST") }
            jsonPath("$.error.message") { value("동시 처리 충돌로 요청을 완료하지 못했습니다.") }
            content { string(not(containsString(CLIENT_ORDER_ID))) }
            content { string(not(containsString("t_order_terminal_id_client_order_id_key"))) }
        }.andDo {
            handle(
                document(
                    "orders-create-concurrent-request",
                    AUTHORIZATION_HEADER,
                    requestFields(HEADER_FIELDS + LINE_FIELDS),
                    responseFields(failFields("CONCURRENT_REQUEST 고정 코드. 같은 clientOrderId의 생성이 겹쳤다. 이 endpoint는 clientOrderId로 멱등이므로 다시 보내면 먼저 저장된 주문과 내용이 같을 때 그 주문을 받고, 다르면 409 IDEMPOTENCY_CONFLICT를 받는다")),
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
                    AUTHORIZATION_HEADER,
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
    fun `문서 길이 상한을 넘는 본문은 읽는 단계에서 400으로 끊긴다`() {
        // 문자열 하나하나는 상한(65536자) 안이고 문서 전체만 1 MiB를 넘긴다.
        // 이렇게 갈라 놓아야 어느 상한이 끊었는지가 테스트로 드러난다.
        val shortLine = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 1 }"""

        postOrder(requestJson(lines = List(20_000) { shortLine }.joinToString(","))).andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("FAIL") }
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `문자열 길이 상한을 넘는 값은 문서가 작아도 400으로 끊긴다`() {
        // 본문 전체는 약 70 KB로 1 MiB에 한참 못 미치고 문자열 하나만 65536자를 넘긴다.
        val longName = """{ "productCode": "SKU-1", "productName": "${"a".repeat(70_000)}", "unitPrice": "4500", "quantity": 1 }"""

        postOrder(requestJson(lines = longName)).andExpect {
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
                    AUTHORIZATION_HEADER,
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

    private fun postOrder(body: String, authorization: String? = BEARER): ResultActionsDsl = mockMvc.post("/v1/orders") {
        contentType = MediaType.APPLICATION_JSON
        authorization?.let { header("Authorization", it) }
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
        private const val OTHER_ID = "018f6b1a-0000-7000-8000-0000000000ff"
        private const val CLIENT_ORDER_ID = "018f6b1a-0000-7000-8000-0000000000a3"
        private val krw: Currency = Currency.getInstance("KRW")

        private fun command(
            lines: List<OrderCommand.Create.Line> = listOf(OrderFixtures.line("4500", 2)),
        ) = OrderCommand.Create(
            storeId = StoreId(UUID.fromString(STORE_ID)),
            terminalId = TerminalId(UUID.fromString(TERMINAL_ID)),
            clientOrderId = ClientOrderId(UUID.fromString(CLIENT_ORDER_ID)),
            currency = krw,
            lines = lines,
        )

        private fun requestJson(
            terminalId: String = TERMINAL_ID,
            storeId: String = STORE_ID,
            currency: String = "KRW",
            unitPrice: String = "4500",
            lines: String = """{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "$unitPrice", "quantity": 2 }""",
        ) = """
            {
              "storeId": "$storeId",
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
            fieldWithPath("lines[].productCode").type(JsonFieldType.STRING).description("상품 코드. 비어 있거나 공백뿐이면 BLANK_PRODUCT_CODE, 64자를 넘으면 PRODUCT_CODE_TOO_LONG, NUL 문자(코드 0번 글자 U+0000, null 값과 다르다)가 있으면 INVALID_PRODUCT_CODE"),
            fieldWithPath("lines[].productName").type(JsonFieldType.STRING).description("상품명. 비어 있거나 공백뿐이면 BLANK_PRODUCT_NAME, 255자를 넘으면 PRODUCT_NAME_TOO_LONG, NUL 문자(코드 0번 글자 U+0000, null 값과 다르다)가 있으면 INVALID_PRODUCT_NAME"),
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
