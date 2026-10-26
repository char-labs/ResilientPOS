package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuCommand
import com.resilientpos.catalog.MenuFixtures
import com.resilientpos.catalog.MenuManagement
import com.resilientpos.catalog.MenuView
import com.resilientpos.catalog.internal.domain.Menu
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.withCsrf
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.MockTerminalRateLimiter
import com.resilientpos.terminal.TerminalManagement
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put
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
 * 점주 메뉴 API 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·헤더 해석·직렬화·오류 변환·문서 계약만 검사한다.
 * 실제 세션·CSRF·DB와 ETag 이어짐은 `MenuApiIntegrationTest`가 본다.
 */
@MockTerminalRateLimiter
@ExtendWith(OutputCaptureExtension::class)
@WebMvcTest(MenuOwnerController::class)
@AutoConfigureRestDocs
@MockitoBean(types = [MenuManagement::class, TerminalManagement::class])
class MenuOwnerControllerTest(
    private val mockMvc: MockMvc,
    private val management: MenuManagement,
) {

    private val owner: RequestPostProcessor = authentication(UsernamePasswordAuthenticationToken.authenticated(OWNER_ID.toString(), null, emptyList()))

    @Test
    fun `조회하면 200과 메뉴 본문을 돌려주고 가격은 decimal string이며 ETag는 매장 ID와 version이다`() {
        given(management.find(StoreId(STORE_ID), OwnerId(OWNER_ID))).willReturn(VIEW)

        mockMvc.perform(get("/owner/v1/stores/{storeId}/menu", STORE_ID).with(owner))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"$STORE_ID.3\""))
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.currency").value("KRW"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].code").value("A1"))
            .andExpect(jsonPath("$.data.items[0].name").value("아메리카노"))
            .andExpect(jsonPath("$.data.items[0].price").value("4000"))
            .andExpect(jsonPath("$.data.items[0].category").value("커피"))
            .andExpect(jsonPath("$.data.items[1].price").value("4500"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "menu-owner-find",
                    STORE_PATH_PARAMETERS,
                    responseHeaders(ETAG_HEADER),
                    responseFields(menuFields()),
                ),
            )
    }

    @Test
    fun `메뉴가 없으면 404 MENU_NOT_FOUND이고 ETag가 없다`() {
        given(management.find(StoreId(STORE_ID), OwnerId(OWNER_ID)))
            .willThrow(DomainException(MenuManagement.MENU_NOT_FOUND, "메뉴를 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        mockMvc.perform(get("/owner/v1/stores/{storeId}/menu", STORE_ID).with(owner))
            .andExpect(status().isNotFound)
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("MENU_NOT_FOUND"))
            .andDo(document("menu-owner-find-not-found", STORE_PATH_PARAMETERS, responseFields(failFields("MENU_NOT_FOUND. 이 매장에 메뉴가 아직 없다. 처음 만들 때는 `If-None-Match: *`로 PUT한다"))))
    }

    @Test
    fun `남의 매장이나 없는 매장의 조회는 404 STORE_NOT_FOUND다`() {
        given(management.find(StoreId(STORE_ID), OwnerId(OWNER_ID)))
            .willThrow(DomainException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        mockMvc.perform(get("/owner/v1/stores/{storeId}/menu", STORE_ID).with(owner))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("STORE_NOT_FOUND"))
    }

    @Test
    fun `로그인하지 않은 조회는 401 UNAUTHENTICATED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(get("/owner/v1/stores/{storeId}/menu", STORE_ID))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))

        verifyNoInteractions(management)
    }

    private fun put(body: String = BODY, ifMatch: String? = null, ifNoneMatch: String? = null, csrf: Boolean = true, user: Boolean = true): ResultActions =
        mockMvc.perform(
            put("/owner/v1/stores/{storeId}/menu", STORE_ID)
                .apply { if (user) with(owner) }
                .apply { if (csrf) withCsrf() }
                .apply { ifMatch?.let { header("If-Match", it) } }
                .apply { ifNoneMatch?.let { header("If-None-Match", it) } }
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun replaceCommand(precondition: MenuCommand.Precondition, currency: String = "KRW") =
        MenuFixtures.replace(StoreId(STORE_ID), OwnerId(OWNER_ID), precondition, currency, ITEMS)

    @Test
    fun `If-None-Match 별표로 만들면 200과 version 0의 ETag를 돌려주고 감사 로그를 남긴다`(output: CapturedOutput) {
        given(management.replace(replaceCommand(MenuCommand.Precondition.Create))).willReturn(view(0))

        put(ifNoneMatch = "*")
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"$STORE_ID.0\""))
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.currency").value("KRW"))
            .andExpect(jsonPath("$.data.items[1].price").value("4500"))
            .andDo(
                document(
                    "menu-owner-replace-create",
                    STORE_PATH_PARAMETERS, CSRF_HEADER, requestHeaders(IF_NONE_MATCH_HEADER), REQUEST_FIELDS, responseHeaders(ETAG_HEADER), responseFields(menuFields()),
                ),
            )

        val audit = output.all.lines().single { "action=MENU_REPLACED" in it }
        assertTrue("ownerId=$OWNER_ID" in audit && "storeId=$STORE_ID" in audit && "version=0" in audit && "items=2" in audit, audit)
    }

    @Test
    fun `If-Match로 고치면 version을 커맨드로 옮기고 새 ETag를 돌려준다`() {
        given(management.replace(replaceCommand(MenuCommand.Precondition.Version(3)))).willReturn(view(4))

        put(ifMatch = "\"$STORE_ID.3\"")
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"$STORE_ID.4\""))
            .andDo(document("menu-owner-replace-update", STORE_PATH_PARAMETERS, CSRF_HEADER, requestHeaders(IF_MATCH_HEADER), REQUEST_FIELDS, responseHeaders(ETAG_HEADER), responseFields(menuFields())))
    }

    @Test
    fun `옛 ETag로 저장하면 409 MENU_VERSION_MISMATCH이고 감사 로그가 없다`(output: CapturedOutput) {
        given(management.replace(replaceCommand(MenuCommand.Precondition.Version(2))))
            .willThrow(DomainException(MenuManagement.MENU_VERSION_MISMATCH, "메뉴가 다른 곳에서 먼저 바뀌었습니다", DomainErrorKind.CONFLICT))

        put(ifMatch = "\"$STORE_ID.2\"")
            .andExpect(status().isConflict)
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.error.errorCode").value("MENU_VERSION_MISMATCH"))
            .andDo(document("menu-owner-replace-version-mismatch", STORE_PATH_PARAMETERS, CSRF_HEADER, requestHeaders(IF_MATCH_HEADER), responseFields(failFields("MENU_VERSION_MISMATCH. 받은 ETag 뒤에 메뉴가 바뀌었거나, 만들기인데 이미 있거나, `If-Match`가 지금 ETag 하나가 아니다. 다시 GET해서 받은 ETag로 저장한다"))))

        assertTrue("action=MENU_REPLACED" !in output.all)
    }

    @Test
    fun `조건 헤더가 없거나 둘 다 있으면 400 MENU_PRECONDITION_REQUIRED이고 Facade를 부르지 않는다`() {
        put()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("MENU_PRECONDITION_REQUIRED"))
            .andDo(document("menu-owner-replace-precondition-required", STORE_PATH_PARAMETERS, CSRF_HEADER, responseFields(failFields("MENU_PRECONDITION_REQUIRED. `If-Match`와 `If-None-Match` 중 정확히 하나를 보내야 한다. 둘 다 없거나 둘 다 있으면 이 오류다"))))
        put(ifMatch = "\"$STORE_ID.3\"", ifNoneMatch = "*")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("MENU_PRECONDITION_REQUIRED"))

        verifyNoInteractions(management)
    }

    @Test
    fun `If-Match가 이 매장의 strong ETag 하나가 아니면 409 MENU_VERSION_MISMATCH이고 500이 아니다`() {
        val values = listOf(
            "*",
            "W/\"$STORE_ID.3\"",
            "\"$STORE_ID.3\", \"$STORE_ID.4\"",
            "\"a\",\"b\"",
            "$STORE_ID.3",
            "\"$OTHER_STORE_ID.3\"",
            "\"$STORE_ID.x\"",
            "\"$STORE_ID.-1\"",
            "\"$STORE_ID.99999999999999999999\"",
            "\"3\"",
            "",
        )
        values.forEach { value ->
            put(ifMatch = value)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.error.errorCode").value("MENU_VERSION_MISMATCH"))
        }

        verifyNoInteractions(management)
    }

    @Test
    fun `If-None-Match가 별표가 아니면 409 MENU_VERSION_MISMATCH이다`() {
        listOf("\"$STORE_ID.0\"", "W/\"x\"", "* ", "**").forEach { value ->
            put(ifNoneMatch = value)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.error.errorCode").value("MENU_VERSION_MISMATCH"))
        }

        verifyNoInteractions(management)
    }

    @Test
    fun `남의 매장이나 없는 매장의 저장은 404 STORE_NOT_FOUND다`() {
        given(management.replace(replaceCommand(MenuCommand.Precondition.Create)))
            .willThrow(DomainException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        put(ifNoneMatch = "*")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("STORE_NOT_FOUND"))
            .andDo(document("menu-owner-replace-store-not-found", STORE_PATH_PARAMETERS, CSRF_HEADER, responseFields(failFields("STORE_NOT_FOUND. 없는 매장과 남의 매장을 구분하지 않는다"))))
    }

    @Test
    fun `메뉴 검증에 실패하면 400 INVALID_MENU이고 통화는 코드 문자열 그대로 Facade에 넘어간다`() {
        given(management.replace(replaceCommand(MenuCommand.Precondition.Create, currency = "krw")))
            .willThrow(DomainException(Menu.INVALID_MENU, "알 수 없는 통화 코드입니다", DomainErrorKind.INVALID))

        put(body = BODY.replace("\"KRW\"", "\"krw\""), ifNoneMatch = "*")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_MENU"))
            .andDo(document("menu-owner-replace-invalid-menu", STORE_PATH_PARAMETERS, CSRF_HEADER, responseFields(failFields("INVALID_MENU. 코드·이름·분류의 길이나 공백, 코드 중복, 항목 201개 이상, 모르는 통화 코드, 음수 가격. 메시지에 몇 번째 항목의 무엇인지 적는다. 지원하지 않는 통화와 자릿수 초과는 `UNSUPPORTED_CURRENCY`·`INVALID_MONEY_SCALE`"))))
    }

    @Test
    fun `필수 필드가 빠지면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        put(body = """{ "items": [] }""", ifNoneMatch = "*")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))

        verifyNoInteractions(management)
    }

    @Test
    fun `CSRF 토큰이 없는 저장은 403 ACCESS_DENIED이고 Facade를 부르지 않는다`() {
        put(ifNoneMatch = "*", csrf = false)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.errorCode").value("ACCESS_DENIED"))

        verifyNoInteractions(management)
    }

    @Test
    fun `로그인하지 않은 저장은 401 UNAUTHENTICATED이고 Facade를 부르지 않는다`() {
        put(ifNoneMatch = "*", user = false)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))

        verifyNoInteractions(management)
    }

    companion object {
        private val OWNER_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000b1")
        private val STORE_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000c1")
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

        private val OTHER_STORE_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000c2")

        private val ITEMS = listOf(MenuFixtures.item("A1", "아메리카노", "4000", "커피"), MenuFixtures.item("A2", "라테", "4500", "커피"))

        private val BODY = """
            {
              "currency": "KRW",
              "items": [
                { "code": "A1", "name": "아메리카노", "price": "4000", "category": "커피" },
                { "code": "A2", "name": "라테", "price": "4500", "category": "커피" }
              ]
            }
        """.trimIndent()

        private fun view(version: Long) = VIEW.copy(version = version)

        private val CSRF_HEADER = requestHeaders(
            headerWithName("X-XSRF-TOKEN").description("`GET /owner/v1/csrf`가 심은 `__Host-XSRF-TOKEN` 쿠키의 값. 없거나 쿠키와 다르면 403 ACCESS_DENIED"),
        )

        private val IF_MATCH_HEADER = headerWithName("If-Match").description("고칠 때 보낸다. 마지막 GET·PUT이 준 ETag 하나(`\"매장ID.version\"`). `*`·weak(`W/`)·여러 값·다른 매장의 ETag는 409 MENU_VERSION_MISMATCH. `If-None-Match`와 함께 보내지 않는다")

        private val IF_NONE_MATCH_HEADER = headerWithName("If-None-Match").description("처음 만들 때 `*`만 보낸다. 다른 값이거나 메뉴가 이미 있으면 409 MENU_VERSION_MISMATCH. `If-Match`와 함께 보내지 않는다")

        private val REQUEST_FIELDS = requestFields(
            fieldWithPath("currency").type(JsonFieldType.STRING).description("메뉴 통화(ISO 4217 코드, 대문자). 모든 가격이 이 통화다"),
            fieldWithPath("items").type(JsonFieldType.ARRAY).description("메뉴 전체. 저장하면 이 배열로 통째로 교체된다. 0~200개이고 배열 순서가 표시 순서다"),
            fieldWithPath("items[].code").type(JsonFieldType.STRING).description("상품 코드. 1~64자이고 메뉴 안에서 유일하다"),
            fieldWithPath("items[].name").type(JsonFieldType.STRING).description("상품 이름. 1~255자"),
            fieldWithPath("items[].price").type(JsonFieldType.STRING).description("가격. 메뉴 통화 자릿수의 decimal string이고 0 이상이다"),
            fieldWithPath("items[].category").type(JsonFieldType.STRING).description("분류. 1~50자"),
        )

        private val STORE_PATH_PARAMETERS = pathParameters(parameterWithName("storeId").description("메뉴를 다루는 매장 ID(UUID). 내 매장이 아니거나 없으면 404 STORE_NOT_FOUND"))

        private val ETAG_HEADER = headerWithName("ETag").description("`\"매장ID.version\"` 형식의 strong ETag. 수정할 때 `If-Match`에 그대로 싣는다")

        private fun menuFields(): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data.currency").type(JsonFieldType.STRING).description("메뉴 통화(ISO 4217). 모든 가격이 이 통화다"),
            fieldWithPath("data.items").type(JsonFieldType.ARRAY).description("메뉴 항목. 배열 순서가 표시 순서이고 분류 탭 순서는 분류가 처음 나온 순서다"),
            fieldWithPath("data.items[].code").type(JsonFieldType.STRING).description("상품 코드. 메뉴 안에서 유일하다"),
            fieldWithPath("data.items[].name").type(JsonFieldType.STRING).description("상품 이름"),
            fieldWithPath("data.items[].price").type(JsonFieldType.STRING).description("가격. 메뉴 통화 자릿수의 decimal string"),
            fieldWithPath("data.items[].category").type(JsonFieldType.STRING).description("분류. 한 상품은 분류 하나에 속한다"),
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
