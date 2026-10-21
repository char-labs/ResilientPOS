package com.resilientpos.platform.deadletter

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 플랫폼 운영자의 DLT endpoint. `/admin` 아래는 `AdminSecurityFilterChain`이 운영자 토큰과 권한으로 지킨다. principal은 검증된 토큰이고 운영자는 그 `sub`다.
 * 목록 조회는 로그를 남기지 않는다(오류는 advice가 남긴다). 재투입의 감사 로그는 [DeadLetterRedriver]가 남긴다.
 */
@RestController
class DeadLetterController(
    private val reader: DeadLetterReader,
    private val redriver: DeadLetterRedriver,
) {

    @GetMapping("/admin/v1/dead-letters")
    fun list(
        @RequestParam topic: String,
        @RequestParam partition: Int,
        @RequestParam(defaultValue = "0") fromOffset: Long,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DeadLetterResponse> = reader.read(topic, partition, fromOffset, limit)

    @PostMapping("/admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive")
    fun redrive(
        @PathVariable topic: String,
        @PathVariable partition: Int,
        @PathVariable offset: Long,
        @RequestBody request: DeadLetterRedriveRequest,
        @AuthenticationPrincipal operator: Jwt,
    ): DeadLetterRedriveResponse {
        // resource server는 sub를 검사하지 않는다. sub 없는 토큰은 IdP 설정 오류라 기록 없이 500으로 끝낸다.
        val subject = checkNotNull(operator.subject) { "운영자 토큰에 sub가 없다" }
        return redriver.redrive(topic, partition, offset, request.reason.orEmpty(), subject)
    }
}
