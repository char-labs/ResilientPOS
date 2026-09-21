package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.RequestCheckoutCommand
import com.resilientpos.order.RequestCheckoutResult
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.CannotAcquireLockException
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `POST /v1/orders/{orderId}/checkout` 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·오류 변환만 검사한다.
 * 경로 변수를 문서화하려면 URL 템플릿이 필요해서 Kotlin DSL 대신 REST Docs의 request builder를 쓴다.
 */
@WebMvcTest(OrderController::class)
@AutoConfigureRestDocs
class OrderCheckoutControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var facade: OrderCommandFacade

    private fun requestCheckout(orderId: String): ResultActions =
        mockMvc.perform(post("/v1/orders/{orderId}/checkout", orderId))

    @Test
    fun `경로의 orderId를 RequestCheckoutCommand로 옮기고 200과 확정 금액을 돌려준다`() {
        given(facade.requestCheckout(COMMAND))
            .willReturn(RequestCheckoutResult(COMMAND.orderId, OrderStatus.CHECKOUT_REQUESTED, 1, Money.of("9000", krw)))

        requestCheckout(ORDER_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
            .andExpect(jsonPath("$.data.status").value("CHECKOUT_REQUESTED"))
            .andExpect(jsonPath("$.data.checkoutNo").value(1))
            .andExpect(jsonPath("$.data.amount").value("9000"))
            .andExpect(jsonPath("$.data.currency").value("KRW"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "orders-checkout",
                    PATH_PARAMETERS,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.orderId").type(JsonFieldType.STRING).description("주문 ID(UUID)"),
                        fieldWithPath("data.status").type(JsonFieldType.STRING).description("주문의 현재 상태. 성공하면 CHECKOUT_REQUESTED"),
                        fieldWithPath("data.checkoutNo").type(JsonFieldType.NUMBER).description("결제 시도 회차. 재요청이면 이미 확정된 회차"),
                        fieldWithPath("data.amount").type(JsonFieldType.STRING).description("이 회차에 확정한 금액. 주문 통화 기준 decimal string"),
                        fieldWithPath("data.currency").type(JsonFieldType.STRING).description("ISO 4217 통화 코드"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(facade).requestCheckout(COMMAND)
    }

    @Test
    fun `소수 자리가 있는 통화의 금액은 통화 자릿수 그대로 나간다`() {
        val usd = Currency.getInstance("USD")
        given(facade.requestCheckout(COMMAND))
            .willReturn(RequestCheckoutResult(COMMAND.orderId, OrderStatus.CHECKOUT_REQUESTED, 1, Money.of("30.30", usd)))

        requestCheckout(ORDER_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.amount").value("30.30"))
            .andExpect(jsonPath("$.data.currency").value("USD"))
    }

    @Test
    fun `Facade가 ORDER_NOT_FOUND를 던지면 404이고 도메인 오류 코드를 담는다`() {
        given(facade.requestCheckout(COMMAND))
            .willThrow(DomainException("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        requestCheckout(ORDER_ID)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("ORDER_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("주문을 찾을 수 없습니다"))
            .andDo(document("orders-checkout-not-found", PATH_PARAMETERS, responseFields(failFields("도메인 오류 코드"))))
    }

    @Test
    fun `Facade가 잠금 대기 초과를 던지면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다`() {
        given(facade.requestCheckout(COMMAND)).willThrow(CannotAcquireLockException("could not obtain lock on row in relation t_order"))

        requestCheckout(ORDER_ID)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.error.errorCode").value("CONCURRENT_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("같은 대상에 대한 다른 요청을 처리하고 있습니다. 다시 요청해 주세요."))
            .andDo(
                document(
                    "orders-checkout-concurrent-request",
                    PATH_PARAMETERS,
                    responseFields(failFields("CONCURRENT_REQUEST 고정 코드. 같은 요청을 다시 보내면 된다")),
                ),
            )
    }

    @Test
    fun `orderId가 UUID 형식이 아니면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        requestCheckout("not-a-uuid")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
            .andDo(document("orders-checkout-bad-request", PATH_PARAMETERS, responseFields(failFields("BAD_REQUEST 고정 코드"))))

        verifyNoInteractions(facade)
    }

    companion object {
        private const val ORDER_ID = "00000000-0000-0000-0000-0000000000a1"
        private val COMMAND = RequestCheckoutCommand(OrderId(UUID.fromString(ORDER_ID)))
        private val krw: Currency = Currency.getInstance("KRW")

        private val PATH_PARAMETERS = pathParameters(parameterWithName("orderId").description("checkout을 요청할 주문 ID(UUID)"))

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
