package com.resilientpos.store.internal.adapter.web

import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.logger
import com.resilientpos.store.StoreCommand
import com.resilientpos.store.StoreManagement
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 점주용 매장 endpoint. `/owner` 아래는 `OwnerSecurityFilterChain`이 세션 쿠키와 CSRF 토큰으로 지킨다. principal은 점주 ID(UUID)다.
 * Facade가 커밋해 돌아오면 감사 로그 한 줄을 남긴다.
 */
@RestController
@RequestMapping("/owner/v1/stores")
class StoreController(private val management: StoreManagement) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateStoreRequest, @AuthenticationPrincipal ownerId: UUID): StoreResponse {
        val result = management.create(StoreCommand.Create(OwnerId(ownerId), request.name))
        log.info { "audit action=STORE_CREATED ownerId=$ownerId storeId=${result.storeId.value}" }
        return StoreResponse(result.storeId.value, result.name)
    }

    @GetMapping
    fun findMine(@AuthenticationPrincipal ownerId: UUID): List<StoreResponse> =
        management.findByOwner(OwnerId(ownerId)).map(StoreResponse::from)

    private companion object {
        val log by logger()
    }
}
