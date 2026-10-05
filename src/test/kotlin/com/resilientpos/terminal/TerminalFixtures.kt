package com.resilientpos.terminal

import com.resilientpos.store.StoreId
import java.util.UUID
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders

/** 웹 슬라이스와 통합 테스트가 v1 경로에 붙이는 단말 인증의 공용 fixture. */
object TerminalFixtures {
    const val TOKEN = "test-terminal-token"
    const val BEARER = "Bearer $TOKEN"

    /** REST Docs 요청 헤더 스니펫. 인증이 걸린 모든 endpoint 문서에 넣는다. */
    val AUTHORIZATION_HEADER = requestHeaders(
        headerWithName("Authorization").description("단말 토큰. Bearer 형식이며 없거나 유효하지 않으면 401 UNAUTHENTICATED"),
    )
}

/** 실제 [TerminalManagement.register]로 만든 단말. 통합 테스트가 본문·경로에 쓸 식별자와 요청 헤더를 한 곳에서 얻는다. */
data class RegisteredTerminal(val terminalId: TerminalId, val storeId: StoreId, val token: String) {
    val bearer: String get() = "Bearer $token"
}

fun TerminalManagement.registerTerminal(storeId: StoreId = StoreId(UUID.randomUUID())): RegisteredTerminal {
    val registered = register(TerminalCommand.Register(storeId))
    return RegisteredTerminal(registered.terminalId, storeId, registered.token)
}
