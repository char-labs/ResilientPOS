package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.foundation.DomainException
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderId
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.application.SyncCommand
import com.resilientpos.sync.internal.application.SyncCommandFacade
import com.resilientpos.sync.internal.application.SyncItemStatus
import com.resilientpos.sync.internal.domain.CheckoutPayload
import com.resilientpos.sync.internal.domain.OperationPayload
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalId
import java.math.BigDecimal
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
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `POST /v1/terminals/{terminalId}/sync` 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·오류 변환만 검사한다.
 * 경로 변수를 문서화하려면 URL 템플릿이 필요해서 Kotlin DSL 대신 REST Docs의 request builder를 쓴다.
 */
@WebMvcTest(SyncController::class)
@AutoConfigureRestDocs
class SyncControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var facade: SyncCommandFacade

    private fun postBatch(body: String): ResultActions =
        mockMvc.perform(post("/v1/terminals/{terminalId}/sync", TERMINAL_ID).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `배치를 커맨드로 옮겨 Facade를 부르고 200과 항목별 결과·watermark를 돌려준다`() {
        val orderId = OrderId(UUID.fromString("018f6b1a-0000-7000-8000-000000000001"))
        given(facade.receiveBatch(command(operation(1, ORDER_COMMAND), operation(2, CHECKOUT_COMMAND))))
            .willReturn(
                SyncCommand.ReceiveBatch.Result(
                    lastAckedSequence = 2,
                    items = listOf(
                        SyncCommand.ReceiveBatch.Item(OPERATION_ID_1, SyncItemStatus.APPLIED, orderId, null),
                        SyncCommand.ReceiveBatch.Item(OPERATION_ID_2, SyncItemStatus.REJECTED, null, "ORDER_NOT_FOUND"),
                    ),
                ),
            )

        postBatch(requestJson(operationJson(OPERATION_ID_1, 1), checkoutJson(OPERATION_ID_2, 2)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.lastAckedSequence").value(2))
            .andExpect(jsonPath("$.data.items[0].operationId").value(OPERATION_ID_1.toString()))
            .andExpect(jsonPath("$.data.items[0].status").value("APPLIED"))
            .andExpect(jsonPath("$.data.items[0].orderId").value(orderId.value.toString()))
            .andExpect(jsonPath("$.data.items[1].status").value("REJECTED"))
            .andExpect(jsonPath("$.data.items[1].errorCode").value("ORDER_NOT_FOUND"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "sync-receive-batch",
                    PATH_PARAMETERS,
                    requestFields(REQUEST_FIELDS),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.lastAckedSequence").type(JsonFieldType.NUMBER).description("배치가 끝난 시점에 서버가 확정한 마지막 순번. 단말은 이 다음 순번부터 다시 보낸다"),
                        fieldWithPath("data.items[].operationId").type(JsonFieldType.STRING).description("요청 항목의 operationId. items는 요청 순서가 아니라 sequence 오름차순이다"),
                        fieldWithPath("data.items[].status").type(JsonFieldType.STRING).description("APPLIED(적용)·DUPLICATE(확정된 operation의 재전송)·REJECTED(도메인 규칙 거절, 순번 확정)·GAP(앞 순번 미확정)·CONFLICT(확정된 순번에 다른 내용이나 모르는 operation)"),
                        fieldWithPath("data.items[].orderId").type(JsonFieldType.STRING).optional().description("적용됐거나 적용된 기록의 재전송이면 서버 주문 ID(UUID), 아니면 null"),
                        fieldWithPath("data.items[].errorCode").type(JsonFieldType.STRING).optional().description("거절됐거나 거절된 기록의 재전송이면 도메인 오류 코드, 아니면 null"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        // Mockito matcher는 Kotlin non-null 파라미터에 null을 넘겨 NPE가 나므로 data class 동등성으로 검증한다.
        verify(facade).receiveBatch(command(operation(1, ORDER_COMMAND), operation(2, CHECKOUT_COMMAND)))
    }

    @Test
    fun `kind가 없거나 모르는 값이면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        val noKindWithoutOrder = """{ "operationId": "$OPERATION_ID_1", "sequence": 1, "clientOrderId": "$CLIENT_ORDER_ID" }"""
        val validCreateOrder = operationJson(OPERATION_ID_1, 1)
        val noKind = validCreateOrder.replace(""""kind": "CREATE_ORDER",""", "")
        val unknownKind = validCreateOrder.replace(""""kind": "CREATE_ORDER"""", """"kind": "REFUND"""")
        check(noKind != validCreateOrder && unknownKind != validCreateOrder)

        for (operation in listOf(noKindWithoutOrder, noKind, unknownKind)) {
            postBatch(requestJson(operation))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `amount가 없는 REQUEST_CHECKOUT 항목은 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        val noAmount = checkoutJson(OPERATION_ID_1, 1).replace(""", "amount": "3502"""", "")
        check(noAmount != checkoutJson(OPERATION_ID_1, 1))

        postBatch(requestJson(noAmount))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))

        verifyNoInteractions(facade)
    }

    @Test
    fun `amount가 null이거나 빈 문자열이거나 숫자가 아닌 문자열이면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        val valid = checkoutJson(OPERATION_ID_1, 1)

        for (amount in listOf("null", "\"\"", "\"abc\"")) {
            val invalid = valid.replace("\"3502\"", amount)
            check(invalid != valid)

            postBatch(requestJson(invalid))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
        }

        verifyNoInteractions(facade)
    }

    @Test
    fun `amount가 JSON 숫자여도 받아들여 같은 CheckoutPayload로 옮긴다`() {
        val number = checkoutJson(OPERATION_ID_1, 1).replace("\"3502\"", "3502")
        given(facade.receiveBatch(command(operation(1, CHECKOUT_COMMAND)))).willReturn(SyncCommand.ReceiveBatch.Result(1, emptyList()))

        postBatch(requestJson(number)).andExpect(status().isOk)

        verify(facade).receiveBatch(command(operation(1, CHECKOUT_COMMAND)))
    }

    @Test
    fun `빈 배치도 200이고 응답은 watermark 조회다`() {
        given(facade.receiveBatch(command())).willReturn(SyncCommand.ReceiveBatch.Result(7, emptyList()))

        postBatch(requestJson())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.lastAckedSequence").value(7))
            .andExpect(jsonPath("$.data.items").isEmpty)

        verify(facade).receiveBatch(command())
    }

    @Test
    fun `Facade가 SYNC_BATCH_TOO_LARGE를 던지면 400이고 도메인 오류 코드를 담는다`() {
        given(facade.receiveBatch(command(operation(1, ORDER_COMMAND))))
            .willThrow(DomainException("SYNC_BATCH_TOO_LARGE", "한 배치의 operation은 500개를 넘을 수 없습니다"))

        postBatch(requestJson(operationJson(OPERATION_ID_1, 1)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("SYNC_BATCH_TOO_LARGE"))
            .andDo(
                document(
                    "sync-receive-batch-too-large",
                    PATH_PARAMETERS,
                    requestFields(subsectionWithPath("operations").description("operation 배열. 500개를 넘으면 SYNC_BATCH_TOO_LARGE. 본문은 1 MiB 이하여야 하며 넘으면 BAD_REQUEST이므로 단말은 배치를 나눈다")),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
                        fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description("도메인 오류 코드"),
                        fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
                    ),
                ),
            )
    }

    companion object {
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val CLIENT_ORDER_ID = "018f6b1a-0000-7000-8000-0000000000a3"
        private val OPERATION_ID_1: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000b1")
        private val OPERATION_ID_2: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000b2")

        private val ORDER_COMMAND = OrderPayload(
            storeId = StoreId(UUID.fromString(STORE_ID)),
            clientOrderId = ClientOrderId(UUID.fromString(CLIENT_ORDER_ID)),
            currency = "KRW",
            lines = listOf(OrderPayload.Line("SKU-1", "아메리카노", BigDecimal("4500"), BigDecimal(2))),
        )

        private val CHECKOUT_COMMAND = CheckoutPayload(ClientOrderId(UUID.fromString(CLIENT_ORDER_ID)), BigDecimal("3502"))

        private fun operation(sequence: Long, payload: OperationPayload) =
            SyncCommand.ReceiveBatch.Operation(if (sequence == 1L) OPERATION_ID_1 else OPERATION_ID_2, sequence, payload)

        private fun command(vararg operations: SyncCommand.ReceiveBatch.Operation) =
            SyncCommand.ReceiveBatch(TerminalId(UUID.fromString(TERMINAL_ID)), operations.toList())

        private fun operationJson(operationId: UUID, sequence: Long) = """
            {
              "operationId": "$operationId",
              "sequence": $sequence,
              "kind": "CREATE_ORDER",
              "order": {
                "storeId": "$STORE_ID",
                "clientOrderId": "$CLIENT_ORDER_ID",
                "currency": "KRW",
                "lines": [{ "productCode": "SKU-1", "productName": "아메리카노", "unitPrice": "4500", "quantity": 2 }]
              }
            }
        """.trimIndent()

        private fun checkoutJson(operationId: UUID, sequence: Long) = """
            { "operationId": "$operationId", "sequence": $sequence, "kind": "REQUEST_CHECKOUT", "clientOrderId": "$CLIENT_ORDER_ID", "amount": "3502" }
        """.trimIndent()

        private fun requestJson(vararg operations: String) = """{ "operations": [${operations.joinToString(",")}] }"""

        private val PATH_PARAMETERS = pathParameters(parameterWithName("terminalId").description("배치를 보낸 단말 ID(UUID). 항목의 주문은 모두 이 단말의 것이다"))

        private val REQUEST_FIELDS: List<FieldDescriptor> = listOf(
            fieldWithPath("operations[].operationId").type(JsonFieldType.STRING).description("단말이 부여한 operation ID(UUID v7). 재전송 판별 키"),
            fieldWithPath("operations[].sequence").type(JsonFieldType.NUMBER).description("단말 안에서 단조 증가하는 순번. 1부터 시작하고 서버는 watermark 다음 순번만 적용한다"),
            fieldWithPath("operations[].kind").type(JsonFieldType.STRING).description("operation 종류. 필수이며 CREATE_ORDER(주문 생성) 또는 REQUEST_CHECKOUT(결제 요청)만 받는다. 없거나 모르는 값이면 배치 전체가 400 BAD_REQUEST"),
            fieldWithPath("operations[].clientOrderId").type(JsonFieldType.STRING).optional().description("REQUEST_CHECKOUT일 때만 있다. 결제를 요청할 주문의 clientOrderId(UUID). 같은 단말의 앞선 CREATE_ORDER가 만든 주문을 가리킨다"),
            fieldWithPath("operations[].amount").type(JsonFieldType.STRING).optional().description("REQUEST_CHECKOUT일 때만 있고 그 항목에서는 필수다. 단말이 본 주문 합계로 주문 통화 기준 decimal string이다. JSON 숫자도 받지만 문자열로 보내기를 권한다. 재전송 때 소수 자릿수(scale)가 바뀌면(3502와 3502.0) 다른 내용으로 판정되어 CONFLICT다. 통화 자릿수·범위에 맞지 않으면 그 항목은 REJECTED(INVALID_MONEY_SCALE 등)이고, 서버의 확정 금액과 다르면 그 항목은 REJECTED(CHECKOUT_AMOUNT_MISMATCH)이고 주문은 그대로다. 없으면 배치 전체가 400 BAD_REQUEST"),
            fieldWithPath("operations[].order.storeId").type(JsonFieldType.STRING).optional().description("CREATE_ORDER일 때만 있는 order 객체의 매장 ID(UUID). order 아래 필드는 모두 CREATE_ORDER 항목에만 있다"),
            fieldWithPath("operations[].order.clientOrderId").type(JsonFieldType.STRING).optional().description("단말이 부여한 주문 생성 요청 ID(UUID). 주문 멱등 키"),
            fieldWithPath("operations[].order.currency").type(JsonFieldType.STRING).optional().description("대문자 ISO 4217 코드. ISO 4217에 없으면 그 항목은 REJECTED(UNSUPPORTED_CURRENCY). 소문자·공백이 붙은 값·금액을 표현할 수 없는 통화(XXX)도 같은 거절. null이면 배치 전체가 400"),
            fieldWithPath("operations[].order.lines[].productCode").type(JsonFieldType.STRING).optional().description("상품 코드. 비어 있으면 그 항목은 REJECTED(BLANK_PRODUCT_CODE), 64자(문자 수, code point)를 넘으면 REJECTED(PRODUCT_CODE_TOO_LONG), NUL 문자가 있으면 REJECTED(INVALID_PRODUCT_CODE)"),
            fieldWithPath("operations[].order.lines[].productName").type(JsonFieldType.STRING).optional().description("상품명. 비어 있으면 그 항목은 REJECTED(BLANK_PRODUCT_NAME), 255자(문자 수, code point)를 넘으면 REJECTED(PRODUCT_NAME_TOO_LONG), NUL 문자가 있으면 REJECTED(INVALID_PRODUCT_NAME)"),
            fieldWithPath("operations[].order.lines[].unitPrice").type(JsonFieldType.STRING).optional().description("단가. 주문 통화 기준 decimal string. 자릿수·범위·통화 규칙에 어긋나면 그 항목이 REJECTED(INVALID_MONEY_SCALE 등)"),
            fieldWithPath("operations[].order.lines[].quantity").type(JsonFieldType.NUMBER).optional().description("정수. 1 미만이거나 소수이거나 Int 범위 밖이면 그 항목은 REJECTED(INVALID_QUANTITY). 숫자가 아니면 배치 전체가 400"),
        )
    }
}
