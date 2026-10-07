package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.platform.withCsrf
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalCommand
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.TerminalQuery
import com.resilientpos.terminal.TerminalQueryFacade
import com.resilientpos.terminal.TerminalStatus
import com.resilientpos.terminal.TerminalView
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
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
 * 단말 관리 API 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·오류 변환·문서 계약만 검사한다.
 * 슬라이스에는 Spring Session이 없어서 점주 인증은 UUID principal의 `authentication()` post-processor로 주고,
 * 상태 변경 요청에는 운영과 같은 CSRF 쿠키·헤더를 싣는다(`withCsrf`).
 * 실제 세션·CSRF 검증은 `OwnerAuthenticationIntegrationTest`가 본다.
 */
@WebMvcTest(TerminalAdminController::class)
@AutoConfigureRestDocs
class TerminalAdminControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var management: TerminalManagement

    @MockitoBean
    private lateinit var queryFacade: TerminalQueryFacade

    private val owner: RequestPostProcessor = authentication(UsernamePasswordAuthenticationToken.authenticated(UUID.fromString(OWNER_ID), null, emptyList()))

    private fun postJson(url: String, body: String?, vararg vars: Any): ResultActions =
        mockMvc.perform(post(url, *vars).with(owner).withCsrf().contentType(MediaType.APPLICATION_JSON).apply { body?.let { content(it) } })

    private fun register(code: String = CODE): ResultActions =
        postJson("/owner/v1/terminals", """{ "storeId": "$STORE_ID", "terminalCode": "$code" }""")

    @Test
    fun `등록하면 201과 terminalId terminalCode token을 돌려주고 커맨드로 옮긴다`() {
        given(management.register(REGISTER)).willReturn(TerminalCommand.Register.Result(TerminalId(UUID.fromString(TERMINAL_ID)), TerminalCode(CODE), TOKEN))

        register()
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.terminalId").value(TERMINAL_ID))
            .andExpect(jsonPath("$.data.terminalCode").value(CODE))
            .andExpect(jsonPath("$.data.token").value(TOKEN))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "terminals-register", CSRF_HEADER,
                    requestFields(
                        fieldWithPath("storeId").type(JsonFieldType.STRING).description("단말이 속할 매장 ID(UUID). 매장 존재는 확인하지 않는다"),
                        fieldWithPath("terminalCode").type(JsonFieldType.STRING).description("매장 안에서 유일한 단말 코드. 영대문자·숫자·하이픈 1~32자이며 소문자는 거절한다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.terminalId").type(JsonFieldType.STRING).description("단말 ID(UUID)"),
                        fieldWithPath("data.terminalCode").type(JsonFieldType.STRING).description("요청한 코드 그대로"),
                        fieldWithPath("data.token").type(JsonFieldType.STRING).description("평문 단말 토큰. 저장하지 않으므로 이 응답이 유일한 기회다. 잃으면 회전한다"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )

        verify(management).register(REGISTER)
    }

    @Test
    fun `코드 형식이 틀리면 400 INVALID_TERMINAL_CODE이고 Facade를 부르지 않는다`() {
        register("lower-case")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_TERMINAL_CODE"))
            .andDo(document("terminals-register-invalid-code", CSRF_HEADER, responseFields(failFields("INVALID_TERMINAL_CODE. 영대문자·숫자·하이픈 1~32자가 아니다"))))

        verifyNoInteractions(management)
    }

    @Test
    fun `같은 매장에 같은 코드면 409 TERMINAL_CODE_DUPLICATED다`() {
        given(management.register(REGISTER)).willThrow(codeDuplicated())

        register()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.errorCode").value("TERMINAL_CODE_DUPLICATED"))
            .andDo(document("terminals-register-duplicated", CSRF_HEADER, responseFields(failFields("TERMINAL_CODE_DUPLICATED. 같은 매장에 같은 코드가 있다. 다른 매장은 같은 코드를 쓸 수 있다"))))
    }

    @Test
    fun `회전하면 200과 새 token을 돌려준다`() {
        given(management.rotateCredential(TerminalCommand.RotateCredential(TerminalId(UUID.fromString(TERMINAL_ID))))).willReturn(TerminalCommand.RotateCredential.Result(TOKEN))

        postJson("/owner/v1/terminals/{terminalId}/credential/rotate", null, TERMINAL_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.token").value(TOKEN))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "terminals-rotate", CSRF_HEADER, TERMINAL_PATH,
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.token").type(JsonFieldType.STRING).description("새 평문 토큰. 옛 토큰은 회전 커밋부터 401이다. 응답을 잃으면 다시 회전한다"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
    }

    @Test
    fun `회전할 단말이 없으면 404 TERMINAL_NOT_FOUND다`() {
        given(management.rotateCredential(TerminalCommand.RotateCredential(TerminalId(UUID.fromString(TERMINAL_ID)))))
            .willThrow(DomainException("TERMINAL_NOT_FOUND", "단말을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        postJson("/owner/v1/terminals/{terminalId}/credential/rotate", null, TERMINAL_ID)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("TERMINAL_NOT_FOUND"))
            .andDo(document("terminals-rotate-not-found", CSRF_HEADER, TERMINAL_PATH, responseFields(failFields("TERMINAL_NOT_FOUND"))))
    }

    @Test
    fun `비활성 단말의 회전은 409 TERMINAL_INACTIVE다`() {
        given(management.rotateCredential(TerminalCommand.RotateCredential(TerminalId(UUID.fromString(TERMINAL_ID)))))
            .willThrow(DomainException("TERMINAL_INACTIVE", "비활성 단말입니다", DomainErrorKind.CONFLICT))

        postJson("/owner/v1/terminals/{terminalId}/credential/rotate", null, TERMINAL_ID)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.errorCode").value("TERMINAL_INACTIVE"))
            .andDo(document("terminals-rotate-inactive", CSRF_HEADER, TERMINAL_PATH, responseFields(failFields("TERMINAL_INACTIVE. 비활성 단말은 되살리지 않는다"))))
    }

    @Test
    fun `비활성화하면 200과 terminalId와 INACTIVE를 돌려주고 이미 비활성이어도 같다`() {
        val id = TerminalId(UUID.fromString(TERMINAL_ID))
        given(management.deactivate(TerminalCommand.Deactivate(id)))
            .willReturn(TerminalCommand.Deactivate.Result(id, TerminalStatus.ACTIVE, TerminalStatus.INACTIVE))
            .willReturn(TerminalCommand.Deactivate.Result(id, TerminalStatus.INACTIVE, TerminalStatus.INACTIVE))

        fun deactivate(): ResultActions = postJson("/owner/v1/terminals/{terminalId}/deactivate", null, TERMINAL_ID)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.terminalId").value(TERMINAL_ID))
            .andExpect(jsonPath("$.data.status").value("INACTIVE"))
            .andExpect(jsonPath("$.data.before").doesNotExist())

        deactivate().andDo(
            document(
                "terminals-deactivate", CSRF_HEADER, TERMINAL_PATH,
                responseFields(
                    fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                    fieldWithPath("data.terminalId").type(JsonFieldType.STRING).description("단말 ID(UUID)"),
                    fieldWithPath("data.status").type(JsonFieldType.STRING).description("항상 INACTIVE. 이미 비활성이어도 같은 응답이다(멱등). 되돌리지 않는다. 비활성 단말의 미전송 큐는 받지 않으므로 큐를 살려야 하는 교체는 비활성화가 아니라 회전을 쓴다"),
                    fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                ),
            ),
        )
        deactivate()
    }

    @Test
    fun `비활성화할 단말이 없으면 404 TERMINAL_NOT_FOUND다`() {
        given(management.deactivate(TerminalCommand.Deactivate(TerminalId(UUID.fromString(TERMINAL_ID)))))
            .willThrow(DomainException("TERMINAL_NOT_FOUND", "단말을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND))

        postJson("/owner/v1/terminals/{terminalId}/deactivate", null, TERMINAL_ID)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("TERMINAL_NOT_FOUND"))
            .andDo(document("terminals-deactivate-not-found", CSRF_HEADER, TERMINAL_PATH, responseFields(failFields("TERMINAL_NOT_FOUND"))))
    }

    @Test
    fun `목록은 200과 항목을 코드 순 그대로 돌려주고 토큰 필드가 없다`() {
        given(queryFacade.findByStore(TerminalQuery.FindByStore(StoreId(UUID.fromString(STORE_ID))))).willReturn(
            listOf(
                TerminalView(TerminalId(UUID.fromString(TERMINAL_ID)), TerminalCode("POS-1"), TerminalStatus.ACTIVE),
                TerminalView(TerminalId(UUID.fromString(OTHER_TERMINAL_ID)), TerminalCode("POS-2"), TerminalStatus.INACTIVE),
            ),
        )

        val body = mockMvc.perform(get("/owner/v1/stores/{storeId}/terminals", STORE_ID).with(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].terminalId").value(TERMINAL_ID))
            .andExpect(jsonPath("$.data[0].terminalCode").value("POS-1"))
            .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data[1].terminalCode").value("POS-2"))
            .andExpect(jsonPath("$.data[1].status").value("INACTIVE"))
            .andDo(
                document(
                    "terminals-list",
                    pathParameters(parameterWithName("storeId").description("단말을 조회할 매장 ID(UUID)")),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data[]").type(JsonFieldType.ARRAY).description("그 매장의 단말. terminalCode 오름차순이다. 없으면 빈 배열"),
                        fieldWithPath("data[].terminalId").type(JsonFieldType.STRING).description("단말 ID(UUID). 등록 응답을 잃었으면 이 값으로 회전한다"),
                        fieldWithPath("data[].terminalCode").type(JsonFieldType.STRING).description("매장 안에서 유일한 단말 코드"),
                        fieldWithPath("data[].status").type(JsonFieldType.STRING).description("ACTIVE 또는 INACTIVE. 토큰은 싣지 않는다"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            ).andReturn().response.contentAsString

        assertFalse(body.contains("token"))
    }

    @Test
    fun `경로 식별자가 UUID 형식이 아니면 400 BAD_REQUEST이고 Facade를 부르지 않는다`() {
        postJson("/owner/v1/terminals/{terminalId}/deactivate", null, "not-a-uuid")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))

        verifyNoInteractions(management, queryFacade)
    }

    @Test
    fun `로그인하지 않으면 401 UNAUTHENTICATED이고 WWW-Authenticate가 없으며 Facade를 부르지 않는다`() {
        mockMvc.perform(get("/owner/v1/stores/{storeId}/terminals", STORE_ID))
            .andExpect(status().isUnauthorized)
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
            .andDo(document("terminals-unauthenticated", responseFields(failFields("UNAUTHENTICATED. 세션 쿠키가 없거나 만료됐거나 로그아웃했다. 원인을 나누지 않는다"))))

        verifyNoInteractions(queryFacade)
    }

    @Test
    fun `CSRF 토큰이 없는 상태 변경 요청은 403 ACCESS_DENIED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(post("/owner/v1/terminals/{terminalId}/deactivate", TERMINAL_ID).with(owner))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.errorCode").value("ACCESS_DENIED"))
            .andDo(document("terminals-access-denied", responseFields(failFields("ACCESS_DENIED. 상태 변경 요청에 `X-XSRF-TOKEN` 헤더가 없거나 `XSRF-TOKEN` 쿠키와 다르다"))))

        verifyNoInteractions(management)
    }

    companion object {
        private const val OWNER_ID = "018f6b1a-0000-7000-8000-0000000000b1"
        private const val STORE_ID = "018f6b1a-0000-7000-8000-0000000000a1"
        private const val TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a2"
        private const val OTHER_TERMINAL_ID = "018f6b1a-0000-7000-8000-0000000000a3"
        private const val CODE = "POS-1"
        private const val TOKEN = "dGVzdC10ZXJtaW5hbC10b2tlbi1maXh0dXJlLTAwMDA"
        private val REGISTER = TerminalCommand.Register(StoreId(UUID.fromString(STORE_ID)), TerminalCode(CODE))

        private val CSRF_HEADER = requestHeaders(
            headerWithName("X-XSRF-TOKEN").description("`GET /owner/v1/csrf`가 심은 `XSRF-TOKEN` 쿠키의 값. 없거나 쿠키와 다르면 403 ACCESS_DENIED. 세션 쿠키가 없거나 만료되면 401 UNAUTHENTICATED"),
        )
        private val TERMINAL_PATH = pathParameters(parameterWithName("terminalId").description("대상 단말 ID(UUID)"))

        private fun codeDuplicated() = DomainException("TERMINAL_CODE_DUPLICATED", "같은 매장에 같은 단말 코드가 이미 있습니다", DomainErrorKind.CONFLICT)

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
