package com.resilientpos.platform

import com.jayway.jsonpath.JsonPath
import jakarta.servlet.http.Cookie
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

/** 점주 통합 테스트의 공용 비밀번호. 정책(15자 이상)을 맞춘다. */
const val OWNER_PASSWORD = "correct-horse-battery"

/** 로그인해서 얻은 세션 쿠키와 그 로그인이 새로 심은 CSRF 토큰. */
data class OwnerSession(val session: Cookie, val xsrf: String, val ownerId: UUID, val email: String)

/** `X-XSRF-TOKEN` 헤더와 같은 값의 `XSRF-TOKEN` 쿠키를 요청에 싣는다(CSRF 쿠키 방식의 통과 조건). */
fun MockHttpServletRequestDsl.withCsrf(token: String) {
    cookie(Cookie("XSRF-TOKEN", token))
    header("X-XSRF-TOKEN", token)
}

/** 슬라이스용 고정 CSRF 토큰. 문서 예시에 그대로 실린다. */
const val SLICE_CSRF_TOKEN = "3f1c9a52-7d0e-4c1b-9a6e-0b2d8f4e5a71"

/**
 * 웹 슬라이스의 MockMvc 요청 빌더에 CSRF 쿠키와 헤더를 싣는다. `csrf()` post-processor는 `csrf.spa()`의 헤더 검증(원본 토큰)과
 * 맞지 않는 인코딩된 값을 헤더에 넣으므로, 운영과 같은 쿠키 방식을 고정 토큰으로 직접 재현한다.
 */
fun MockHttpServletRequestBuilder.withCsrf(token: String = SLICE_CSRF_TOKEN): MockHttpServletRequestBuilder =
    cookie(Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)

/** 로그인한 점주로 요청한다. 상태 변경 요청에도 통과하도록 CSRF 토큰을 함께 싣는다. */
fun MockHttpServletRequestDsl.asOwner(owner: OwnerSession) {
    cookie(owner.session)
    withCsrf(owner.xsrf)
}

fun MockMvc.issueCsrfToken(): String =
    checkNotNull(get("/owner/v1/csrf").andReturn().response.getCookie("XSRF-TOKEN")) { "XSRF-TOKEN 쿠키가 없다" }.value

fun uniqueOwnerEmail(): String = "owner-${UUID.randomUUID()}@example.com"

/** 실제 체인으로 가입 → CSRF 토큰 → 로그인을 거친 새 점주 세션을 만든다. */
fun MockMvc.logInNewOwner(email: String = uniqueOwnerEmail()): OwnerSession {
    val csrf = issueCsrfToken()
    val body = """{ "email": "$email", "password": "$OWNER_PASSWORD" }"""
    post("/owner/v1/signup") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        withCsrf(csrf)
    }.andExpect { status { isCreated() } }
    val login = post("/owner/v1/login") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        withCsrf(csrf)
    }.andExpect { status { isOk() } }.andReturn().response
    return OwnerSession(
        session = checkNotNull(login.getCookie("SESSION")) { "SESSION 쿠키가 없다" },
        xsrf = checkNotNull(login.getCookie("XSRF-TOKEN")) { "로그인 응답에 새 XSRF-TOKEN 쿠키가 없다" }.value,
        ownerId = UUID.fromString(JsonPath.read(login.contentAsString, "$.data.ownerId")),
        email = email,
    )
}
