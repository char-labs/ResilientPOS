package com.resilientpos.owner.internal.adapter.web

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.owner.OwnerCommand
import com.resilientpos.owner.OwnerId
import com.resilientpos.owner.OwnerManagement
import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.platform.UnauthenticatedException
import com.resilientpos.platform.withCsrf
import com.resilientpos.terminal.TerminalManagement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 점주 가입·로그인·로그아웃·CSRF 토큰 웹 슬라이스. Facade는 [MockitoBean]으로 대체하고 HTTP 매핑·세션·CSRF·오류 변환·문서 계약만 검사한다.
 * 실제 Redis 세션 저장소와 세션 ID 교체는 `OwnerAuthenticationIntegrationTest`가 본다.
 */
@WebMvcTest(OwnerController::class)
@AutoConfigureRestDocs
@ExtendWith(OutputCaptureExtension::class)
class OwnerControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var management: OwnerManagement

    // 슬라이스에 단말 인증 인터셉터 설정이 실린다. 이 테스트의 경로는 v1이 아니라 인증을 타지 않지만 의존성은 채워야 한다.
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    private fun postJson(url: String, body: String): ResultActions =
        mockMvc.perform(post(url).withCsrf().contentType(MediaType.APPLICATION_JSON).content(body))

    private fun signUp(email: String = EMAIL, password: String = PASSWORD): ResultActions =
        postJson("/owner/v1/signup", """{ "email": "$email", "password": "$password" }""")

    private fun logIn(password: String = PASSWORD): ResultActions =
        postJson("/owner/v1/login", """{ "email": "$EMAIL", "password": "$password" }""")

    @Test
    fun `가입하면 201과 ownerId email을 돌려주고 로그인시키지 않으며 감사 로그에 비밀번호와 이메일이 없다`(output: CapturedOutput) {
        given(management.signUp(OwnerCommand.SignUp(EMAIL, PASSWORD))).willReturn(OwnerCommand.SignUp.Result(OwnerId(OWNER_ID), EMAIL))

        val result = signUp()
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.ownerId").value(OWNER_ID.toString()))
            .andExpect(jsonPath("$.data.email").value(EMAIL))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "owner-signup", CSRF_HEADER,
                    requestFields(
                        fieldWithPath("email").type(JsonFieldType.STRING).description("로그인에 쓸 이메일. 앞뒤 공백을 지우고 소문자로 바꿔 저장한다. 254자 이하이며 `@` 앞뒤에 공백 없는 문자가 있어야 한다"),
                        fieldWithPath("password").type(JsonFieldType.STRING).description("비밀번호. 15자 이상, UTF-8 72바이트 이하. 조합 규칙은 없다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.ownerId").type(JsonFieldType.STRING).description("점주 ID(UUID)"),
                        fieldWithPath("data.email").type(JsonFieldType.STRING).description("정규화된 이메일"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            ).andReturn()

        verify(management).signUp(OwnerCommand.SignUp(EMAIL, PASSWORD))
        assertNull(result.request.getSession(false), "가입은 세션을 만들지 않는다")
        val audit = output.all.lines().single { "action=OWNER_SIGNED_UP" in it }
        assertTrue("ownerId=$OWNER_ID" in audit, audit)
        assertFalse(output.all.contains(PASSWORD) || audit.contains(EMAIL), audit)
    }

    @Test
    fun `이메일 형식이 틀리면 400 INVALID_EMAIL이고 감사 로그를 남기지 않는다`(output: CapturedOutput) {
        given(management.signUp(OwnerCommand.SignUp("not-an-email", PASSWORD)))
            .willThrow(DomainException(Owner.INVALID_EMAIL, "이메일 형식이 올바르지 않습니다", DomainErrorKind.INVALID))

        signUp("not-an-email")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_EMAIL"))
            .andDo(document("owner-signup-invalid-email", CSRF_HEADER, responseFields(failFields("INVALID_EMAIL. 이메일 형식이 틀리다"))))

        assertFalse(output.all.contains("action=OWNER_SIGNED_UP"))
    }

    @Test
    fun `비밀번호 정책을 못 맞추면 400 INVALID_PASSWORD다`() {
        given(management.signUp(OwnerCommand.SignUp(EMAIL, "short")))
            .willThrow(DomainException(Owner.INVALID_PASSWORD, "비밀번호는 15자 이상이어야 합니다", DomainErrorKind.INVALID))

        signUp(password = "short")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_PASSWORD"))
            .andDo(document("owner-signup-invalid-password", CSRF_HEADER, responseFields(failFields("INVALID_PASSWORD. 15자 미만이거나 UTF-8 72바이트를 넘는다"))))
    }

    @Test
    fun `같은 이메일이면 409 OWNER_EMAIL_DUPLICATED다`() {
        given(management.signUp(OwnerCommand.SignUp(EMAIL, PASSWORD)))
            .willThrow(DomainException(Owner.OWNER_EMAIL_DUPLICATED, "이미 가입한 이메일입니다", DomainErrorKind.CONFLICT))

        signUp()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.errorCode").value("OWNER_EMAIL_DUPLICATED"))
            .andDo(document("owner-signup-duplicated", CSRF_HEADER, responseFields(failFields("OWNER_EMAIL_DUPLICATED. 대소문자만 다른 이메일도 같은 이메일이다"))))
    }

    @Test
    fun `로그인하면 200과 ownerId email을 돌려주고 세션에 점주 ID 인증을 저장하며 새 CSRF 토큰 쿠키를 심는다`(output: CapturedOutput) {
        given(management.logIn(OwnerCommand.LogIn(EMAIL, PASSWORD))).willReturn(OwnerCommand.LogIn.Result(OwnerId(OWNER_ID), EMAIL))

        val result = logIn()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.ownerId").value(OWNER_ID.toString()))
            .andExpect(jsonPath("$.data.email").value(EMAIL))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.startsWith("XSRF-TOKEN=")))
            .andDo(
                document(
                    "owner-login", FIXED_SET_COOKIE, CSRF_HEADER,
                    requestFields(
                        fieldWithPath("email").type(JsonFieldType.STRING).description("가입한 이메일. 대소문자와 앞뒤 공백은 가입 때와 같은 규칙으로 정규화한다"),
                        fieldWithPath("password").type(JsonFieldType.STRING).description("비밀번호"),
                    ),
                    responseHeaders(
                        headerWithName("Set-Cookie").description("`SESSION` 세션 쿠키(HttpOnly, Secure, SameSite=Lax. 세션 ID는 로그인할 때마다 새로 만든다)와 새 `XSRF-TOKEN` 쿠키. 이후 상태 변경 요청은 새 토큰을 `X-XSRF-TOKEN` 헤더에 싣는다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.ownerId").type(JsonFieldType.STRING).description("점주 ID(UUID)"),
                        fieldWithPath("data.email").type(JsonFieldType.STRING).description("정규화된 이메일"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            ).andReturn()

        val context = assertNotNull(result.request.getSession(false)?.getAttribute("SPRING_SECURITY_CONTEXT") as? org.springframework.security.core.context.SecurityContext)
        assertEquals(OWNER_ID, context.authentication?.principal, "principal은 점주 UUID다")
        assertTrue(context.authentication?.isAuthenticated == true)
        val audit = output.all.lines().single { "action=OWNER_LOGGED_IN" in it }
        assertTrue("ownerId=$OWNER_ID" in audit, audit)
        assertFalse(output.all.contains(PASSWORD) || audit.contains(EMAIL), audit)
    }

    @Test
    fun `자격이 틀리면 401 UNAUTHENTICATED이고 WWW-Authenticate 없이 세션을 만들지 않으며 감사 로그가 없다`(output: CapturedOutput) {
        given(management.logIn(OwnerCommand.LogIn(EMAIL, "wrong-password-value"))).willThrow(UnauthenticatedException())

        val result = logIn("wrong-password-value")
            .andExpect(status().isUnauthorized)
            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
            .andDo(document("owner-login-unauthenticated", CSRF_HEADER, responseFields(failFields("UNAUTHENTICATED. 없는 계정·틀린 비밀번호·잠김을 나누지 않는다"))))
            .andReturn()

        assertNull(result.request.getSession(false))
        assertFalse(output.all.contains("action=OWNER_LOGGED_IN"))
    }

    @Test
    fun `로그아웃하면 204이고 본문이 없으며 세션을 무효화한다`() {
        val session = MockHttpSession()

        mockMvc.perform(post("/owner/v1/logout").session(session).with(authentication(ownerAuthentication())).withCsrf())
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))
            .andDo(document("owner-logout", CSRF_HEADER))

        assertTrue(session.isInvalid, "세션이 무효화되지 않았다")
    }

    @Test
    fun `CSRF 토큰 요청은 204이고 XSRF-TOKEN 쿠키를 심는다`() {
        mockMvc.perform(get("/owner/v1/csrf"))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.startsWith("XSRF-TOKEN=")))
            .andDo(
                document(
                    "owner-csrf", FIXED_SET_COOKIE,
                    responseHeaders(headerWithName("Set-Cookie").description("`XSRF-TOKEN` 쿠키(JavaScript가 읽을 수 있다). 상태 변경 요청마다 값을 `X-XSRF-TOKEN` 헤더에 싣는다")),
                ),
            )
    }

    @Test
    fun `CSRF 토큰이 없으면 403 ACCESS_DENIED이고 Facade를 부르지 않는다`() {
        mockMvc.perform(post("/owner/v1/login").contentType(MediaType.APPLICATION_JSON).content("""{ "email": "$EMAIL", "password": "$PASSWORD" }"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.result").value("FAIL"))
            .andExpect(jsonPath("$.error.errorCode").value("ACCESS_DENIED"))
            .andDo(document("owner-csrf-missing", responseFields(failFields("ACCESS_DENIED. 상태 변경 요청에 `X-XSRF-TOKEN` 헤더가 없거나 `XSRF-TOKEN` 쿠키와 다르다"))))

        verifyNoInteractions(management)
    }

    private fun ownerAuthentication() = UsernamePasswordAuthenticationToken.authenticated(OWNER_ID, null, emptyList())

    companion object {
        private val OWNER_ID: UUID = UUID.fromString("018f6b1a-0000-7000-8000-0000000000b1")
        private const val EMAIL = "owner@example.com"
        private const val PASSWORD = "correct-horse-battery"

        /** 응답마다 달라지는 토큰 값을 문서 예시에서는 고정한다. */
        private val FIXED_SET_COOKIE = preprocessResponse(modifyHeaders().set("Set-Cookie", "XSRF-TOKEN=3f1c9a52-7d0e-4c1b-9a6e-0b2d8f4e5a71; Path=/"))
        private val CSRF_HEADER = requestHeaders(
            headerWithName("X-XSRF-TOKEN").description("`GET /owner/v1/csrf`가 심은 `XSRF-TOKEN` 쿠키의 값. 없거나 쿠키와 다르면 403 ACCESS_DENIED"),
        )

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
