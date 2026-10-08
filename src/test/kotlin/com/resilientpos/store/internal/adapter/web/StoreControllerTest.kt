package com.resilientpos.store.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.withCsrf
import com.resilientpos.store.StoreCommand
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import com.resilientpos.store.StoreView
import com.resilientpos.store.internal.domain.Store
import com.resilientpos.terminal.TerminalManagement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 매장 API 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·오류 변환·문서 계약만 검사한다.
 * 점주 인증은 UUID principal의 `authentication()`으로 주고 상태 변경에는 운영과 같은 CSRF 쿠키·헤더를 싣는다(`withCsrf`).
 */
@WebMvcTest(StoreController::class)
@AutoConfigureRestDocs
@ExtendWith(OutputCaptureExtension::class)
class StoreControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var management: StoreManagement

    // 슬라이스에 단말 인증 인터셉터 설정이 실린다. 이 테스트의 경로는 v1이 아니라 인증을 타지 않지만 의존성은 채워야 한다.
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    private val owner: RequestPostProcessor = authentication(UsernamePasswordAuthenticationToken.authenticated(OWNER_ID.toString(), null, emptyList()))

    private fun create(name: String) = mockMvc.perform(
        post("/owner/v1/stores").with(owner).withCsrf().contentType(MediaType.APPLICATION_JSON).content("""{ "name": "$name" }"""),
    )

    @Test
    fun `생성하면 201과 storeId name을 돌려주고 감사 로그에 점주와 매장만 남긴다`(output: CapturedOutput) {
        given(management.create(StoreCommand.Create(OwnerId(OWNER_ID), "강남점"))).willReturn(StoreCommand.Create.Result(StoreId(STORE_ID), "강남점"))

        create("강남점")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.storeId").value(STORE_ID.toString()))
            .andExpect(jsonPath("$.data.name").value("강남점"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "stores-create", CSRF_HEADER,
                    requestFields(
                        fieldWithPath("name").type(JsonFieldType.STRING).description("매장 이름. 앞뒤 공백을 지운 뒤 1~100자(code point)다. 같은 이름의 매장이 여러 개여도 된다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.storeId").type(JsonFieldType.STRING).description("매장 ID(UUID)"),
                        fieldWithPath("data.name").type(JsonFieldType.STRING).description("앞뒤 공백을 지운 저장된 이름"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        val audit = output.all.lines().single { "action=STORE_CREATED" in it }
        assertTrue("ownerId=$OWNER_ID" in audit && "storeId=$STORE_ID" in audit, audit)
    }

    @Test
    fun `이름이 틀리면 400 INVALID_STORE_NAME이고 감사 로그가 없다`(output: CapturedOutput) {
        given(management.create(StoreCommand.Create(OwnerId(OWNER_ID), " ")))
            .willThrow(DomainException(Store.INVALID_STORE_NAME, "매장 이름은 1~100자여야 합니다", DomainErrorKind.INVALID))

        create(" ")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_STORE_NAME"))
            .andDo(document("stores-create-invalid-name", CSRF_HEADER, responseFields(failFields("INVALID_STORE_NAME. 앞뒤 공백을 지운 이름이 비었거나 100자를 넘는다"))))

        assertTrue("action=STORE_CREATED" !in output.all)
    }

    @Test
    fun `목록은 200과 내 매장 배열을 돌려준다`() {
        given(management.findByOwner(OwnerId(OWNER_ID))).willReturn(listOf(StoreView(StoreId(STORE_ID), "강남점"), StoreView(StoreId(OTHER_STORE_ID), "강남점")))

        mockMvc.perform(get("/owner/v1/stores").with(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].storeId").value(STORE_ID.toString()))
            .andExpect(jsonPath("$.data[0].name").value("강남점"))
            .andExpect(jsonPath("$.data[1].storeId").value(OTHER_STORE_ID.toString()))
            .andDo(
                document(
                    "stores-list",
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data[].storeId").type(JsonFieldType.STRING).description("매장 ID(UUID)"),
                        fieldWithPath("data[].name").type(JsonFieldType.STRING).description("매장 이름"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
    }

    @Test
    fun `로그인하지 않으면 401 UNAUTHENTICATED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(get("/owner/v1/stores"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
            .andDo(document("stores-unauthenticated", responseFields(failFields("UNAUTHENTICATED. 세션 쿠키가 없거나 만료됐거나 로그아웃했다. 원인을 나누지 않는다"))))

        verifyNoInteractions(management)
    }

    @Test
    fun `CSRF 토큰이 없는 생성 요청은 403 ACCESS_DENIED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(post("/owner/v1/stores").with(owner).contentType(MediaType.APPLICATION_JSON).content("""{ "name": "강남점" }"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.errorCode").value("ACCESS_DENIED"))
            .andDo(document("stores-access-denied", responseFields(failFields("ACCESS_DENIED. 상태 변경 요청에 `X-XSRF-TOKEN` 헤더가 없거나 `__Host-XSRF-TOKEN` 쿠키와 다르다"))))

        verifyNoInteractions(management)
    }

    companion object {
        private val OWNER_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000b1")
        private val STORE_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000c1")
        private val OTHER_STORE_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000c2")

        private val CSRF_HEADER = requestHeaders(
            headerWithName("X-XSRF-TOKEN").description("`GET /owner/v1/csrf`가 심은 `__Host-XSRF-TOKEN` 쿠키의 값. 없거나 쿠키와 다르면 403 ACCESS_DENIED"),
        )

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
