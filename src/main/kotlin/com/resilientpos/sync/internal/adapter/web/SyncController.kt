package com.resilientpos.sync.internal.adapter.web

import com.resilientpos.sync.internal.application.SyncCommandFacade
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 단말 sync 공개 endpoint. 요청을 Command로 바꿔 Facade를 부르고 결과를 응답으로 바꾸는 일만 한다.
 * 상태 코드는 배치 처리의 결과다. 항목 판정은 `items[].status`에만 있고 부분 성공을 HTTP 상태로 숨기지 않는다.
 * 경로의 `terminalId`가 인증된 단말과 다르면 403 `TERMINAL_MISMATCH`이고 Facade를 부르지 않는다. 항목 payload의 매장 대조는 Facade가 항목 단위로 한다.
 */
@RestController
@RequestMapping("/v1/terminals/{terminalId}/sync")
class SyncController(private val facade: SyncCommandFacade) {

    /** 빈 배치도 200이고 그때 응답은 watermark 조회다. */
    @PostMapping
    fun receiveBatch(@PathVariable terminalId: UUID, @RequestBody request: SyncBatchRequest, terminal: AuthenticatedTerminal): SyncBatchResponse {
        val id = TerminalId(terminalId)
        terminal.requireSame(id)
        return SyncBatchResponse.from(facade.receiveBatch(request.toCommand(id, terminal.storeId)))
    }
}
