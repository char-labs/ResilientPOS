package com.resilientpos.terminal

import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.TestFixtureMonkey
import com.resilientpos.store.StoreId
import java.util.UUID
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders

/** 웹 슬라이스와 통합 테스트가 v1 경로에 붙이는 단말 인증의 공용 fixture. */
object TerminalFixtures {
    /** 실제 토큰과 같은 형식(base64url 43자). 인터셉터가 형식이 다른 값은 조회 없이 401로 거르므로 고정 fixture도 맞춘다. */
    const val TOKEN = "dGVzdC10ZXJtaW5hbC10b2tlbi1maXh0dXJlLTAwMDA"
    const val BEARER = "Bearer $TOKEN"

    /** REST Docs 요청 헤더 스니펫. 인증이 걸린 모든 endpoint 문서에 넣는다. */
    val AUTHORIZATION_HEADER = requestHeaders(
        headerWithName("Authorization").description("단말 토큰. Bearer 형식이며 없거나 유효하지 않으면 401 UNAUTHENTICATED"),
    )

    /** `TerminalCommand` fixture. 파라미터 이름과 순서는 커맨드 생성자와 같다. */
    fun registerCommand(storeId: StoreId = StoreId(UUID.randomUUID()), terminalCode: TerminalCode = uniqueTerminalCode()): TerminalCommand.Register =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<TerminalCommand.Register>()
            .setExp(TerminalCommand.Register::storeId, storeId)
            .setExp(TerminalCommand.Register::terminalCode, terminalCode)
            .sample()

    fun rotateCredentialCommand(storeId: StoreId, terminalId: TerminalId): TerminalCommand.RotateCredential =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<TerminalCommand.RotateCredential>()
            .setExp(TerminalCommand.RotateCredential::storeId, storeId)
            .setExp(TerminalCommand.RotateCredential::terminalId, terminalId)
            .sample()

    fun deactivateCommand(storeId: StoreId, terminalId: TerminalId): TerminalCommand.Deactivate =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<TerminalCommand.Deactivate>()
            .setExp(TerminalCommand.Deactivate::storeId, storeId)
            .setExp(TerminalCommand.Deactivate::terminalId, terminalId)
            .sample()
}

/** 실제 [TerminalManagement.register]로 만든 단말. 통합 테스트가 본문·경로에 쓸 식별자와 요청 헤더를 한 곳에서 얻는다. */
data class RegisteredTerminal(val terminalId: TerminalId, val storeId: StoreId, val token: String) {
    val bearer: String get() = "Bearer $token"
}

/** 코드는 매장 안에서 유일해야 하므로 호출마다 새 값을 만든다. */
fun uniqueTerminalCode(): TerminalCode = TerminalCode("T-" + UUID.randomUUID().toString().take(8).uppercase())

fun TerminalManagement.registerTerminal(storeId: StoreId = StoreId(UUID.randomUUID())): RegisteredTerminal {
    val registered = register(TerminalFixtures.registerCommand(storeId))
    return RegisteredTerminal(registered.terminalId, storeId, registered.token)
}
