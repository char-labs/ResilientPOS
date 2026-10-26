package com.resilientpos.catalog.internal.adapter.web

import com.resilientpos.catalog.MenuCommand.Precondition
import com.resilientpos.catalog.MenuManagement
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.logger
import com.resilientpos.store.StoreId
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 점주용 메뉴 endpoint. `/owner` 아래는 `OwnerSecurityFilterChain`이 세션 쿠키와 CSRF 토큰으로 지킨다. principal은 점주 ID(UUID 문자열)다.
 * 매장 소유 확인은 Facade가 한다. 조건 헤더(`If-Match`·`If-None-Match`)를 [Precondition]으로 해석하는 것은 HTTP 문법이라 여기서 한다.
 * 헤더가 틀린 요청은 매장 소유를 확인하기 전에 거절한다. 어느 쪽이든 매장의 존재를 드러내지 않는다.
 */
@RestController
class MenuOwnerController(private val management: MenuManagement) {

    @GetMapping("/owner/v1/stores/{storeId}/menu")
    fun find(@PathVariable storeId: UUID, @AuthenticationPrincipal ownerId: String): ResponseEntity<MenuResponse> {
        val view = management.find(StoreId(storeId), OwnerId(UUID.fromString(ownerId)))
        return ResponseEntity.ok().eTag(MenuEtag.of(view)).body(MenuResponse.from(view))
    }

    @PutMapping("/owner/v1/stores/{storeId}/menu")
    fun replace(
        @PathVariable storeId: UUID,
        @RequestBody request: ReplaceMenuRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        @AuthenticationPrincipal ownerId: String,
    ): ResponseEntity<MenuResponse> {
        val store = StoreId(storeId)
        val precondition = precondition(store, ifMatch, ifNoneMatch)
        val view = management.replace(request.toCommand(store, OwnerId(UUID.fromString(ownerId)), precondition))
        log.info { "audit action=MENU_REPLACED ownerId=$ownerId storeId=$storeId version=${view.version} items=${view.items.size}" }
        return ResponseEntity.ok().eTag(MenuEtag.of(view)).body(MenuResponse.from(view))
    }

    /** 정확히 하나만 보내야 한다. `If-None-Match`는 `*`만, `If-Match`는 이 매장의 strong ETag 하나만 받고 나머지는 모두 409다. */
    private fun precondition(storeId: StoreId, ifMatch: String?, ifNoneMatch: String?): Precondition = when {
        ifMatch != null && ifNoneMatch == null -> Precondition.Version(MenuEtag.versionOf(ifMatch, storeId) ?: throw versionMismatch())
        ifMatch == null && ifNoneMatch != null -> if (ifNoneMatch == "*") Precondition.Create else throw versionMismatch()
        else -> throw DomainException(MENU_PRECONDITION_REQUIRED, "If-Match와 If-None-Match 중 하나만 보내야 합니다", DomainErrorKind.INVALID)
    }

    private fun versionMismatch() =
        DomainException(MenuManagement.MENU_VERSION_MISMATCH, "메뉴가 다른 곳에서 먼저 바뀌었습니다", DomainErrorKind.CONFLICT)

    private companion object {
        const val MENU_PRECONDITION_REQUIRED = "MENU_PRECONDITION_REQUIRED"
        val log by logger()
    }
}
