package com.resilientpos.catalog.internal.adapter.web

import com.jayway.jsonpath.JsonPath
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.platform.OwnerSession
import com.resilientpos.platform.asOwner
import com.resilientpos.platform.logInNewOwner
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.RegisteredTerminal
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.registerTerminal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * 점주 메뉴 API와 단말 메뉴 API를 실제 세션·CSRF 체인, 단말 인증, PostgreSQL까지 잇는다. Docker가 필요하다.
 * PUT 응답의 ETag가 DB version과 이어지는 GET의 ETag와 같은지, 단말의 조건부 요청이 그 ETag로 304를 내는지 본다.
 * 단말 API는 rate limit이 있어 매장마다 호출 수를 적게 둔다.
 */
class MenuApiIntegrationTest(
    private val mockMvc: MockMvc,
    private val terminals: TerminalManagement,
    private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private val owner: OwnerSession = mockMvc.logInNewOwner()
    private val storeId: UUID = createStore(owner)

    private fun createStore(session: OwnerSession): UUID = UUID.fromString(
        JsonPath.read<String>(
            mockMvc.post("/owner/v1/stores") {
                asOwner(session)
                contentType = MediaType.APPLICATION_JSON
                content = """{ "name": "강남점" }"""
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
            "$.data.storeId",
        ),
    )

    private fun body(americanoPrice: String) = """
        { "currency": "KRW", "items": [
            { "code": "A1", "name": "아메리카노", "price": "$americanoPrice", "category": "커피" },
            { "code": "A2", "name": "라테", "price": "4500", "category": "커피" }
        ] }
    """.trimIndent()

    private fun put(
        price: String,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
        session: OwnerSession = owner,
        store: UUID = storeId,
    ): ResultActionsDsl = mockMvc.put("/owner/v1/stores/$store/menu") {
        asOwner(session)
        contentType = MediaType.APPLICATION_JSON
        content = body(price)
        ifMatch?.let { header("If-Match", it) }
        ifNoneMatch?.let { header("If-None-Match", it) }
    }

    private fun ownerGet(session: OwnerSession = owner, store: UUID = storeId): ResultActionsDsl =
        mockMvc.get("/owner/v1/stores/$store/menu") { cookie(session.session) }

    private fun terminalGet(terminal: RegisteredTerminal, ifNoneMatch: String? = null): ResultActionsDsl =
        mockMvc.get("/v1/menu") {
            header("Authorization", terminal.bearer)
            ifNoneMatch?.let { header("If-None-Match", it) }
        }

    private fun ResultActionsDsl.etag(): String = checkNotNull(andReturn().response.getHeader("ETag")) { "ETag 헤더가 없다" }

    @Test
    fun `만들고 고치면 PUT 응답 ETag가 이어지는 GET의 ETag와 같고 같은 내용을 다시 저장해도 version이 오른다`() {
        val created = put("4000", ifNoneMatch = "*").andExpect { status { isOk() } }.etag()
        assertEquals("\"$storeId.0\"", created)
        assertEquals(created, ownerGet().andExpect { status { isOk() } }.etag())

        val updated = put("4500", ifMatch = created).andExpect { status { isOk() } }.etag()
        assertEquals("\"$storeId.1\"", updated)
        ownerGet().andExpect {
            status { isOk() }
            header { string("ETag", updated) }
            jsonPath("$.data.items[0].price") { value("4500") }
        }

        val same = put("4500", ifMatch = updated).andExpect { status { isOk() } }.etag()
        assertEquals("\"$storeId.2\"", same)
        assertEquals(same, ownerGet().etag())
    }

    @Test
    fun `단말은 지금 ETag면 304 옛 ETag나 다른 매장의 같은 version ETag면 200과 지금 메뉴를 받는다`() {
        val terminal = terminals.registerTerminal(StoreId(storeId))
        val first = put("4000", ifNoneMatch = "*").etag()
        terminalGet(terminal).andExpect {
            status { isOk() }
            header { string("ETag", first) }
            jsonPath("$.data.items[0].price") { value("4000") }
        }
        val second = put("4500", ifMatch = first).etag()

        terminalGet(terminal, ifNoneMatch = first).andExpect {
            status { isOk() }
            header { string("ETag", second) }
            jsonPath("$.data.items[0].price") { value("4500") }
        }
        terminalGet(terminal, ifNoneMatch = second).andExpect {
            status { isNotModified() }
            header { string("ETag", second) }
            header { string("Cache-Control", "no-cache, private") }
            content { string("") }
        }
        terminalGet(terminal, ifNoneMatch = "\"${UUID.randomUUID()}.1\"").andExpect {
            status { isOk() }
            header { string("ETag", second) }
        }
    }

    @Test
    fun `옛 ETag로 다시 저장하면 409이고 저장된 메뉴는 그대로이며 조건 헤더가 틀리면 400이나 409이고 500이 아니다`() {
        val first = put("4000", ifNoneMatch = "*").etag()
        val second = put("4500", ifMatch = first).etag()

        put("9999", ifMatch = first).andExpect {
            status { isConflict() }
            jsonPath("$.error.errorCode") { value("MENU_VERSION_MISMATCH") }
        }
        put("9999", ifNoneMatch = "*").andExpect { status { isConflict() } }
        put("9999").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("MENU_PRECONDITION_REQUIRED") }
        }
        put("9999", ifMatch = second, ifNoneMatch = "*").andExpect { status { isBadRequest() } }
        listOf("*", "W/$second", "$second, $second", "\"a\",\"b\"", "${second.trim('"')}", "\"${UUID.randomUUID()}.1\"").forEach {
            put("9999", ifMatch = it).andExpect {
                status { isConflict() }
                jsonPath("$.error.errorCode") { value("MENU_VERSION_MISMATCH") }
            }
        }

        ownerGet().andExpect {
            status { isOk() }
            header { string("ETag", second) }
            jsonPath("$.data.items[0].price") { value("4500") }
        }
    }

    @Test
    fun `메뉴 검증에 실패하면 400 INVALID_MENU이고 저장되지 않는다`() {
        mockMvc.put("/owner/v1/stores/$storeId/menu") {
            asOwner(owner)
            contentType = MediaType.APPLICATION_JSON
            content = """{ "currency": "krw", "items": [] }"""
            header("If-None-Match", "*")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.errorCode") { value("INVALID_MENU") }
        }

        ownerGet().andExpect { status { isNotFound() } }
    }

    @Test
    fun `메뉴가 없으면 점주와 단말 모두 404 MENU_NOT_FOUND이고 ETag가 없다`() {
        ownerGet().andExpect {
            status { isNotFound() }
            header { doesNotExist("ETag") }
            jsonPath("$.error.errorCode") { value("MENU_NOT_FOUND") }
        }
        terminalGet(terminals.registerTerminal(StoreId(storeId))).andExpect {
            status { isNotFound() }
            header { doesNotExist("ETag") }
            jsonPath("$.error.errorCode") { value("MENU_NOT_FOUND") }
        }
    }

    @Test
    fun `남의 매장의 조회와 저장은 404 STORE_NOT_FOUND이고 저장되지 않는다`() {
        val intruder = mockMvc.logInNewOwner()

        ownerGet(intruder).andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("STORE_NOT_FOUND") }
        }
        put("4000", ifNoneMatch = "*", session = intruder).andExpect {
            status { isNotFound() }
            jsonPath("$.error.errorCode") { value("STORE_NOT_FOUND") }
        }
        put("4000", ifNoneMatch = "*", store = UUID.randomUUID()).andExpect { status { isNotFound() } }

        ownerGet().andExpect { status { isNotFound() } }
    }

    @Test
    fun `CSRF 토큰이 없는 저장은 403이고 로그인하지 않은 조회와 저장은 401이다`() {
        mockMvc.put("/owner/v1/stores/$storeId/menu") {
            cookie(owner.session)
            contentType = MediaType.APPLICATION_JSON
            content = body("4000")
            header("If-None-Match", "*")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.errorCode") { value("ACCESS_DENIED") }
        }
        mockMvc.get("/owner/v1/stores/$storeId/menu").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        }

        ownerGet().andExpect { status { isNotFound() } }
    }

    @Test
    fun `토큰이 없는 단말 조회는 401이다`() {
        mockMvc.get("/v1/menu").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        }
    }

    private fun insertRaw(json: String) {
        jdbcTemplate.update(
            "insert into t_menu (store_id, menu, version, created_at, updated_at) values (?, ?::jsonb, 0, now(), now())",
            storeId,
            json,
        )
    }

    @Test
    fun `저장 문서가 지금 규칙 밖이어도 점주와 단말 GET은 200으로 그대로 돌려준다`() {
        val item = """{"code":"A1","name":"${"n".repeat(300)}","price":"4000","category":"커피"}"""
        insertRaw("""{"currency":"KRW","items":[$item,$item]}""")

        ownerGet().andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(2) }
        }
        terminalGet(terminals.registerTerminal(StoreId(storeId))).andExpect { status { isOk() } }
    }

    @Test
    fun `저장 문서를 읽지 못하면 GET은 400이 아니라 500이다`() {
        insertRaw("""{"currency":"XX1","items":[]}""")

        ownerGet().andExpect { status { isInternalServerError() } }
    }
}
