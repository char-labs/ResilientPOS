package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuManagement
import com.resilientpos.terminal.AuthenticatedTerminal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 단말 메뉴 endpoint. 매장은 경로가 아니라 토큰의 [AuthenticatedTerminal.storeId]다.
 * 304 판정은 Spring의 조건부 요청 처리가 응답의 `ETag`와 요청의 `If-None-Match`로 한다. 여기서 헤더를 파싱하지 않는다.
 */
@RestController
class MenuTerminalController(private val management: MenuManagement) {

    @GetMapping("/v1/menu")
    fun find(terminal: AuthenticatedTerminal): ResponseEntity<MenuResponse> {
        val view = management.findForTerminal(terminal.storeId)
        return ResponseEntity.ok()
            .eTag(MenuEtag.of(view))
            .cacheControl(CacheControl.noCache().cachePrivate())
            .body(MenuResponse.from(view))
    }
}
