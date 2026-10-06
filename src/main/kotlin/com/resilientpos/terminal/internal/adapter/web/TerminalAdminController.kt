package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.logger
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalCommand
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.TerminalQuery
import com.resilientpos.terminal.TerminalQueryFacade
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자용 단말 관리 endpoint. `/admin` 아래는 `OperatorSecurityFilterChain`이 OIDC JWT와 `OPERATOR` 역할로 지킨다.
 * 요청을 Command·Query로 바꿔 Facade를 부르고, Facade가 커밋해 돌아오면 감사 로그 한 줄을 남긴다. 토큰은 로그에 남기지 않는다.
 */
@RestController
class TerminalAdminController(
    private val management: TerminalManagement,
    private val queryFacade: TerminalQueryFacade,
) {

    @PostMapping("/admin/v1/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody request: RegisterTerminalRequest, @AuthenticationPrincipal operator: Jwt): RegisterTerminalResponse {
        val result = management.register(TerminalCommand.Register(StoreId(request.storeId), TerminalCode(request.terminalCode)))
        audit(operator, "TERMINAL_REGISTERED", result.terminalId, "storeId=${request.storeId} terminalCode=${result.terminalCode.value}")
        return RegisterTerminalResponse(result.terminalId.value, result.terminalCode.value, result.token)
    }

    @PostMapping("/admin/v1/terminals/{terminalId}/credential/rotate")
    fun rotate(@PathVariable terminalId: UUID, @AuthenticationPrincipal operator: Jwt): RotateCredentialResponse {
        val id = TerminalId(terminalId)
        val result = management.rotateCredential(TerminalCommand.RotateCredential(id))
        audit(operator, "TERMINAL_CREDENTIAL_ROTATED", id)
        return RotateCredentialResponse(result.token)
    }

    /** 이미 비활성이어도 같은 응답이다. 큐를 살려야 하는 교체는 비활성화가 아니라 회전을 쓴다. */
    @PostMapping("/admin/v1/terminals/{terminalId}/deactivate")
    fun deactivate(@PathVariable terminalId: UUID, @AuthenticationPrincipal operator: Jwt): DeactivateTerminalResponse {
        val result = management.deactivate(TerminalCommand.Deactivate(TerminalId(terminalId)))
        audit(operator, "TERMINAL_DEACTIVATED", result.terminalId, "before=${result.before} after=${result.after}")
        return DeactivateTerminalResponse(result.terminalId.value, result.after)
    }

    @GetMapping("/admin/v1/stores/{storeId}/terminals")
    fun findByStore(@PathVariable storeId: UUID): List<TerminalResponse> =
        queryFacade.findByStore(TerminalQuery.FindByStore(StoreId(storeId)))
            .map { TerminalResponse(it.terminalId.value, it.terminalCode.value, it.status) }

    private fun audit(operator: Jwt, action: String, terminalId: TerminalId, detail: String = "") {
        val name = operator.getClaimAsString("preferred_username")?.let { " operatorName=$it" }.orEmpty()
        log.info { "audit action=$action operatorSub=${operator.subject}$name terminalId=${terminalId.value} $detail".trimEnd() }
    }

    private companion object {
        val log by logger()
    }
}
