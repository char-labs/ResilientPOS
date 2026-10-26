package com.resilientpos.order.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderQueryFacade
import com.resilientpos.order.OrderStatus
import com.resilientpos.order.OrderSummaryView
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import com.resilientpos.terminal.MockTerminalRateLimiter
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
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
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
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
 * `GET /owner/v1/stores/{storeId}/orders` 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·소유 확인·파라미터 변환·400 변환·문서 계약을 검사한다.
 * 쿼리 문자열의 `%2B`가 살아 있어야 해서 요청은 MockMvc의 `param` 대신 URI로 만든다.
 */
@MockTerminalRateLimiter
@WebMvcTest(OrderOwnerController::class)
@AutoConfigureRestDocs
@MockitoBean(types = [OrderQueryFacade::class, StoreManagement::class, TerminalManagement::class])
class OrderOwnerControllerTest(
    private val mockMvc: MockMvc,
    private val queryFacade: OrderQueryFacade,
    private val stores: StoreManagement,
) {

    /** 슬라이스가 싣는 단말 인증 인터셉터의 의존성이다. 이 API는 단말을 쓰지 않는다. */
    private val owner: RequestPostProcessor = authentication(UsernamePasswordAuthenticationToken.authenticated(OWNER_ID, null, emptyList()))

    private fun list(query: String = "", asOwner: Boolean = true): ResultActions =
        mockMvc.perform(get(URI.create("/owner/v1/stores/$STORE_ID/orders" + if (query.isEmpty()) "" else "?$query")).apply { if (asOwner) with(owner) })

    private fun criteria(criteria: OrderQuery.FindOrders.Criteria) = OrderQuery.FindOrders(StoreId(UUID.fromString(STORE_ID)), criteria)

    private fun empty() = OrderQuery.FindOrders.Result(emptyList(), null)

    @Test
    fun `기간 상태 크기 cursor를 조회에 싣고 200과 items와 nextCursor를 돌려준다`() {
        given(queryFacade.findOrders(criteria(PAGE_CRITERIA))).willReturn(
            OrderQuery.FindOrders.Result(
                listOf(
                    summary(ORDER_ID, OrderStatus.PAID, "3502", Instant.parse("2026-10-25T23:00:00Z")),
                    summary(OTHER_ORDER_ID, OrderStatus.PAID, "1000", Instant.parse("2026-10-25T22:00:00.123456Z")),
                ),
                OrderQuery.FindOrders.Cursor(Instant.parse("2026-10-25T22:00:00Z"), OrderId(UUID.fromString(LAST_ORDER_ID))),
            ),
        )

        list("from=2026-10-25T00:00:00Z&to=2026-10-26T00:00:00%2B09:00&status=PAID&size=2&cursor=$CURSOR")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].orderId").value(ORDER_ID))
            .andExpect(jsonPath("$.data.items[0].terminalId").value(TERMINAL_ID))
            .andExpect(jsonPath("$.data.items[0].status").value("PAID"))
            .andExpect(jsonPath("$.data.items[0].currency").value("KRW"))
            .andExpect(jsonPath("$.data.items[0].total").value("3502"))
            .andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-10-25T23:00:00Z"))
            .andExpect(jsonPath("$.data.items[1].orderId").value(OTHER_ORDER_ID))
            .andExpect(jsonPath("$.data.items[1].total").value("1000"))
            .andExpect(jsonPath("$.data.items[1].occurredAt").value("2026-10-25T22:00:00.123456Z"))
            .andExpect(jsonPath("$.data.nextCursor").value(NEXT_CURSOR))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "owner-orders-list",
                    queryParameters(
                        parameterWithName("from").optional().description("이 발생 시각 이후(포함)만. ISO-8601 offset 시각(`2026-10-25T00:00:00Z`, `2026-10-26T00:00:00+09:00`). 쿼리 문자열의 offset `+`는 `%2B`로 보내야 한다. 형식이 틀리면 400 BAD_REQUEST"),
                        parameterWithName("to").optional().description("이 발생 시각 전(미포함)까지만. `from`과 같은 형식이고 `from`이 `to`보다 같거나 늦으면 빈 목록"),
                        parameterWithName("status").optional().description("그 상태의 주문만. OPEN, CHECKOUT_REQUESTED, PAID 중 하나이고 모르는 값은 400 BAD_REQUEST"),
                        parameterWithName("size").optional().description("한 페이지 건수. 1~50이고 기본 20. 범위 밖이면 400 BAD_REQUEST"),
                        parameterWithName("cursor").optional().description("이전 응답의 `nextCursor`. 불투명한 문자열이라 해석하지 않고 그대로 보낸다. 해석할 수 없으면 400 BAD_REQUEST. 다른 파라미터는 이전 요청과 같게 보낸다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.items[]").type(JsonFieldType.ARRAY).description("그 매장의 주문. 발생 시각 최신순이고 같은 시각이면 주문 ID 내림차순이다. 정렬은 고정이다. 없으면 빈 배열"),
                        fieldWithPath("data.items[].orderId").type(JsonFieldType.STRING).description("주문 ID(UUID)"),
                        fieldWithPath("data.items[].terminalId").type(JsonFieldType.STRING).description("주문을 만든 단말 ID(UUID)"),
                        fieldWithPath("data.items[].status").type(JsonFieldType.STRING).description("OPEN, CHECKOUT_REQUESTED, PAID"),
                        fieldWithPath("data.items[].currency").type(JsonFieldType.STRING).description("주문 통화(ISO 4217)"),
                        fieldWithPath("data.items[].total").type(JsonFieldType.STRING).description("line 단가 곱하기 수량의 합계. 통화 기준 decimal string"),
                        fieldWithPath("data.items[].occurredAt").type(JsonFieldType.STRING).description("주문이 팔린 시각(ISO-8601 UTC). 서버가 받은 시각이 아니다"),
                        fieldWithPath("data.nextCursor").type(JsonFieldType.STRING).optional().description("다음 페이지를 읽을 cursor. 다음 페이지가 없으면 null"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(stores).requireOwned(StoreId(UUID.fromString(STORE_ID)), OwnerId(UUID.fromString(OWNER_ID)))
    }

    @Test
    fun `다음 페이지가 없으면 nextCursor는 null이다`() {
        given(queryFacade.findOrders(criteria(OrderQuery.FindOrders.Criteria()))).willReturn(
            OrderQuery.FindOrders.Result(listOf(summary(ORDER_ID, OrderStatus.OPEN, "1000", Instant.parse("2026-10-25T23:00:00Z"))), null),
        )

        list()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.nextCursor").value(null))
    }

    @Test
    fun `파라미터가 없으면 기본 크기와 필터 없이 조회한다`() {
        given(queryFacade.findOrders(criteria(OrderQuery.FindOrders.Criteria()))).willReturn(empty())

        list().andExpect(status().isOk).andExpect(jsonPath("$.data.items.length()").value(0))

        verify(queryFacade).findOrders(criteria(OrderQuery.FindOrders.Criteria(size = 20)))
    }

    @Test
    fun `크기 경계 1과 50은 통과하고 조회에 그대로 실린다`() {
        given(queryFacade.findOrders(criteria(OrderQuery.FindOrders.Criteria(size = 1)))).willReturn(empty())
        given(queryFacade.findOrders(criteria(OrderQuery.FindOrders.Criteria(size = 50)))).willReturn(empty())

        list("size=1").andExpect(status().isOk)
        list("size=50").andExpect(status().isOk)

        verify(queryFacade).findOrders(criteria(OrderQuery.FindOrders.Criteria(size = 1)))
        verify(queryFacade).findOrders(criteria(OrderQuery.FindOrders.Criteria(size = 50)))
    }

    @Test
    fun `offset의 플러스를 퍼센트 인코딩한 URI가 통과하고 같은 시각으로 읽힌다`() {
        val criteria = criteria(OrderQuery.FindOrders.Criteria(from = Instant.parse("2026-10-24T15:00:00Z"), to = Instant.parse("2026-10-25T15:00:00Z")))
        given(queryFacade.findOrders(criteria)).willReturn(empty())

        list("from=2026-10-25T00:00:00%2B09:00&to=2026-10-26T00:00:00%2B09:00").andExpect(status().isOk)

        verify(queryFacade).findOrders(criteria)
    }

    @Test
    fun `남의 매장이거나 없는 매장이면 404 STORE_NOT_FOUND이고 size와 cursor를 보기 전에 조회하지 않는다`() {
        willThrow(DomainException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))
            .given(stores).requireOwned(StoreId(UUID.fromString(STORE_ID)), OwnerId(UUID.fromString(OWNER_ID)))

        list("size=0")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("STORE_NOT_FOUND"))
            .andDo(document("owner-orders-list-store-not-found", responseFields(failFields("STORE_NOT_FOUND. 매장이 없거나 로그인한 점주의 것이 아니다. 둘을 구분하지 않는다"))))

        verifyNoInteractions(queryFacade)
    }

    @Test
    fun `로그인하지 않으면 401 UNAUTHENTICATED이고 조회하지 않는다`() {
        list(asOwner = false)
            .andExpect(status().isUnauthorized)
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
            .andDo(document("owner-orders-list-unauthenticated", responseFields(failFields("UNAUTHENTICATED. 세션 쿠키가 없거나 만료됐거나 로그아웃했다. 원인을 나누지 않는다"))))

        verifyNoInteractions(queryFacade, stores)
    }

    @Test
    fun `해석할 수 없는 cursor는 종류마다 400 BAD_REQUEST이고 조회하지 않는다`() {
        val invalid = mapOf(
            "Base64가 아님" to "not%20base64!",
            "구조가 틀림" to raw("1792972200123456"),
            "UUID가 아님" to raw("1792972200123456.not-a-uuid"),
            "축약한 UUID" to raw("1792972200123456.18f6b1a-0-7000-8000-c1"),
            "대문자 UUID" to raw("1792972200123456.018F6B1A-0000-7000-8000-0000000000C1"),
            "숫자가 아님" to raw("abc.018f6b1a-0000-7000-8000-0000000000c1"),
            "시각 범위 밖" to raw("${Long.MAX_VALUE}.018f6b1a-0000-7000-8000-0000000000c1"),
        )

        invalid.values.forEach { cursor ->
            list("cursor=$cursor")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
        }
        list("cursor=not%20base64!")
            .andDo(document("owner-orders-list-bad-request", responseFields(failFields("BAD_REQUEST 고정 코드. 해석할 수 없는 cursor, 범위 밖 size, 모르는 status, 형식이 틀린 from·to가 모두 같다"))))

        verifyNoInteractions(queryFacade)
    }

    @Test
    fun `크기가 범위 밖이거나 숫자가 아니면 400 BAD_REQUEST이고 조회하지 않는다`() {
        listOf("0", "51", "-1", "abc").forEach { size ->
            list("size=$size")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
        }

        verifyNoInteractions(queryFacade)
    }

    @Test
    fun `모르는 status와 형식이 틀린 시각과 저장할 수 없는 연도의 시각은 400 BAD_REQUEST이고 조회하지 않는다`() {
        listOf(
            "status=REFUNDED", "from=yesterday", "to=2026-10-25", "from=2026-10-25T00:00:00",
            "from=%2B10000-01-01T00:00:00Z", "to=-0001-01-01T00:00:00Z",
        ).forEach { query ->
            list(query)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
        }

        verifyNoInteractions(queryFacade)
    }

    companion object {
        private const val OWNER_ID = "018f6b1a-0000-7000-8000-0000000000b1"
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private const val ORDER_ID = "018f6b1a-0000-7000-8000-0000000000c5"
        private const val OTHER_ORDER_ID = "018f6b1a-0000-7000-8000-0000000000c4"
        private const val LAST_ORDER_ID = "018f6b1a-0000-7000-8000-0000000000c3"

        /** 2026-10-25T23:50:00.123456Z와 `018f6b1a-0000-7000-8000-0000000000c1`. 형식은 `OrderCursorCodecTest`가 고정한다. */
        private const val CURSOR = "MTc5Mjk3MjIwMDEyMzQ1Ni4wMThmNmIxYS0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwYzE"

        /** 2026-10-25T22:00:00Z와 [LAST_ORDER_ID]. */
        private const val NEXT_CURSOR = "MTc5Mjk2NTYwMDAwMDAwMC4wMThmNmIxYS0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwYzM"

        private val krw: Currency = Currency.getInstance("KRW")

        private val PAGE_CRITERIA = OrderQuery.FindOrders.Criteria(
            from = Instant.parse("2026-10-25T00:00:00Z"),
            to = Instant.parse("2026-10-25T15:00:00Z"),
            status = OrderStatus.PAID,
            size = 2,
            after = OrderQuery.FindOrders.Cursor(Instant.parse("2026-10-25T23:50:00.123456Z"), OrderId(UUID.fromString("018f6b1a-0000-7000-8000-0000000000c1"))),
        )

        private fun raw(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

        private fun summary(orderId: String, status: OrderStatus, total: String, occurredAt: Instant) = OrderSummaryView(
            orderId = OrderId(UUID.fromString(orderId)),
            terminalId = TerminalId(UUID.fromString(TERMINAL_ID)),
            status = status,
            currency = krw,
            total = Money.of(total, krw),
            occurredAt = occurredAt,
        )

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
