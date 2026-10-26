package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuManagement
import com.resilientpos.catalog.MenuView
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
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
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 단말 메뉴 API 웹 슬라이스. 304 판정은 Spring의 조건부 요청 처리에 맡기므로 여기서는 그 결과(상태, 본문 없음, 헤더)를 단언한다.
 * 단말 인증은 인터셉터가 `TerminalManagement.authenticate`로 한다.
 */
@MockTerminalRateLimiter
@WebMvcTest(MenuTerminalController::class)
@AutoConfigureRestDocs
@MockitoBean(types = [MenuManagement::class, TerminalManagement::class])
class MenuTerminalControllerTest(
    private val mockMvc: MockMvc,
    private val management: MenuManagement,
    private val terminals: TerminalManagement,
) {

    @BeforeEach
    fun authenticate() {
        given(terminals.authenticate(TOKEN)).willReturn(AuthenticatedTerminal(TerminalId(UUID.fromString(TERMINAL_ID)), StoreId(STORE_ID)))
        given(management.findForTerminal(StoreId(STORE_ID))).willReturn(VIEW)
    }

    private fun find(ifNoneMatch: String? = null, authorization: String? = BEARER): ResultActions =
        mockMvc.perform(
            get("/v1/menu")
                .apply { authorization?.let { header("Authorization", it) } }
                .apply { ifNoneMatch?.let { header("If-None-Match", it) } },
        )

    @Test
    fun `인증 단말의 매장 메뉴를 200과 ETag와 no-cache private으로 돌려준다`() {
        find()
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", ETAG))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.currency").value("KRW"))
            .andExpect(jsonPath("$.data.items[0].code").value("A1"))
            .andExpect(jsonPath("$.data.items[0].price").value("4000"))
            .andExpect(jsonPath("$.data.items[1].name").value("라테"))
            .andDo(document("menu-terminal-find", AUTHORIZATION_HEADER, responseHeaders(ETAG_HEADER, CACHE_CONTROL_HEADER), responseFields(menuFields())))
    }

    @Test
    fun `지금 ETag의 If-None-Match는 304이고 본문이 없으며 ETag와 Cache-Control이 남는다`() {
        find(ifNoneMatch = ETAG)
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))
            .andExpect(header().string("ETag", ETAG))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andDo(document("menu-terminal-find-not-modified", CONDITIONAL_REQUEST_HEADERS, responseHeaders(ETAG_HEADER, CACHE_CONTROL_HEADER)))
    }

    @Test
    fun `옛 ETag나 다른 매장의 같은 version ETag는 200과 지금 메뉴다`() {
        listOf("\"$STORE_ID.2\"", "\"$OTHER_STORE_ID.3\"").forEach { stale ->
            find(ifNoneMatch = stale)
                .andExpect(status().isOk)
                .andExpect(header().string("ETag", ETAG))
                .andExpect(jsonPath("$.data.items[0].price").value("4000"))
        }
    }

    @Test
    fun `weak 비교로 일치하는 W 슬래시 ETag도 304다`() {
        find(ifNoneMatch = "W/$ETAG")
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))
    }

    @Test
    fun `메뉴가 없으면 404 MENU_NOT_FOUND이고 ETag가 없다`() {
        given(management.findForTerminal(StoreId(STORE_ID)))
            .willThrow(DomainException(MenuManagement.MENU_NOT_FOUND, "메뉴를 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        find()
            .andExpect(status().isNotFound)
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.error.errorCode").value("MENU_NOT_FOUND"))
            .andDo(document("menu-terminal-find-not-found", AUTHORIZATION_HEADER, responseFields(failFields("MENU_NOT_FOUND. 점주가 아직 이 매장의 메뉴를 만들지 않았다. 단말은 남은 사본으로 판다"))))
    }

    @Test
    fun `Authorization 헤더가 없으면 401 UNAUTHENTICATED와 WWW-Authenticate이다`() {
        find(authorization = null)
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
    }

    companion object {
        private val STORE_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000c1")
        private val OTHER_STORE_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000c2")
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private val ETAG = "\"$STORE_ID.3\""
        private val krw: Currency = Currency.getInstance("KRW")

        private val VIEW = MenuView(
            StoreId(STORE_ID),
            krw,
            listOf(
                MenuView.Item("A1", "아메리카노", Money.of("4000", krw), "커피"),
                MenuView.Item("A2", "라테", Money.of("4500", krw), "커피"),
            ),
            3,
        )

        private val ETAG_HEADER = headerWithName("ETag").description("`\"매장ID.version\"` 형식의 strong ETag. 다음 요청의 `If-None-Match`에 그대로 싣는다")
        private val CACHE_CONTROL_HEADER = headerWithName("Cache-Control").description("`no-cache, private`. 저장은 허용하지만 쓰기 전에 서버에 확인해야 한다(공유 캐시는 저장하지 않는다)")
        private val CONDITIONAL_REQUEST_HEADERS = requestHeaders(
            headerWithName("Authorization").description("단말 토큰. Bearer 형식이며 없거나 유효하지 않으면 401 UNAUTHENTICATED"),
            headerWithName("If-None-Match").description("가진 사본의 ETag. 지금 ETag와 같으면(weak 비교) 304와 빈 본문이다"),
        )

        private fun menuFields(): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data.currency").type(JsonFieldType.STRING).description("메뉴 통화(ISO 4217). 주문 `currency`로 쓴다"),
            fieldWithPath("data.items").type(JsonFieldType.ARRAY).description("메뉴 항목. 배열 순서가 표시 순서이고 분류 탭 순서는 분류가 처음 나온 순서다"),
            fieldWithPath("data.items[].code").type(JsonFieldType.STRING).description("상품 코드"),
            fieldWithPath("data.items[].name").type(JsonFieldType.STRING).description("상품 이름"),
            fieldWithPath("data.items[].price").type(JsonFieldType.STRING).description("가격. 메뉴 통화 자릿수의 decimal string"),
            fieldWithPath("data.items[].category").type(JsonFieldType.STRING).description("분류"),
            fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
        )

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
