package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.logger
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalCommand
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.TerminalManagement
import com.resilientpos.terminal.TerminalQuery
import com.resilientpos.terminal.TerminalQueryFacade
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 점주용 단말 관리 endpoint. `/owner` 아래는 `OwnerSecurityFilterChain`이 세션 쿠키와 CSRF 토큰으로 지킨다. principal은 점주 ID(UUID)다.
 * 모든 endpoint는 먼저 `StoreManagement.requireOwned`로 매장 소유를 확인한다(남의 매장·없는 매장은 `404 STORE_NOT_FOUND`). 요청을 Command·Query로 바꿔 Facade를 부르고, Facade가 커밋해 돌아오면 감사 로그 한 줄을 남긴다. 토큰은 로그에 남기지 않는다.
 */
@RestController
class TerminalOwnerController(
    private val stores: StoreManagement,
    private val management: TerminalManagement,
    private val queryFacade: TerminalQueryFacade,
) {

    @PostMapping("/owner/v1/stores/{storeId}/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@PathVariable storeId: UUID, @RequestBody request: RegisterTerminalRequest, @AuthenticationPrincipal ownerId: UUID): RegisterTerminalResponse {
        stores.requireOwned(StoreId(storeId), OwnerId(ownerId))
        val result = management.register(TerminalCommand.Register(StoreId(storeId), TerminalCode(request.terminalCode)))
        audit(ownerId, "TERMINAL_REGISTERED", storeId, result.terminalId, "terminalCode=${result.terminalCode.value}")
        return RegisterTerminalResponse(result.terminalId.value, result.terminalCode.value, result.token)
    }

    @PostMapping("/owner/v1/stores/{storeId}/terminals/{terminalId}/credential/rotate")
    fun rotate(@PathVariable storeId: UUID, @PathVariable terminalId: UUID, @AuthenticationPrincipal ownerId: UUID): RotateCredentialResponse {
        stores.requireOwned(StoreId(storeId), OwnerId(ownerId))
        val id = TerminalId(terminalId)
        val result = management.rotateCredential(TerminalCommand.RotateCredential(StoreId(storeId), id))
        audit(ownerId, "TERMINAL_CREDENTIAL_ROTATED", storeId, id)
        return RotateCredentialResponse(result.token)
    }

    /** 이미 비활성이어도 같은 응답이다. 큐를 살려야 하는 교체는 비활성화가 아니라 회전을 쓴다. */
    @PostMapping("/owner/v1/stores/{storeId}/terminals/{terminalId}/deactivate")
    fun deactivate(@PathVariable storeId: UUID, @PathVariable terminalId: UUID, @AuthenticationPrincipal ownerId: UUID): DeactivateTerminalResponse {
        stores.requireOwned(StoreId(storeId), OwnerId(ownerId))
        val result = management.deactivate(TerminalCommand.Deactivate(StoreId(storeId), TerminalId(terminalId)))
        audit(ownerId, "TERMINAL_DEACTIVATED", storeId, result.terminalId, "before=${result.before} after=${result.after}")
        return DeactivateTerminalResponse(result.terminalId.value, result.after)
    }

    @GetMapping("/owner/v1/stores/{storeId}/terminals")
    fun findByStore(@PathVariable storeId: UUID, @AuthenticationPrincipal ownerId: UUID): List<TerminalResponse> {
        stores.requireOwned(StoreId(storeId), OwnerId(ownerId))
        return queryFacade.findByStore(TerminalQuery.FindByStore(StoreId(storeId)))
            .map { TerminalResponse(it.terminalId.value, it.terminalCode.value, it.status) }
    }

    private fun audit(ownerId: UUID, action: String, storeId: UUID, terminalId: TerminalId, detail: String = "") {
        log.info { "audit action=$action ownerId=$ownerId storeId=$storeId terminalId=${terminalId.value} $detail".trimEnd() }
    }

    private companion object {
        val log by logger()
    }
}
