package com.resilientpos.platform.deadletter

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 플랫폼 운영자의 DLT endpoint. `/admin` 아래는 `AdminSecurityFilterChain`이 운영자 토큰과 권한으로 지킨다.
 * 목록 조회는 로그를 남기지 않는다(오류는 advice가 남긴다).
 */
@RestController
class DeadLetterController(private val reader: DeadLetterReader) {

    @GetMapping("/admin/v1/dead-letters")
    fun list(
        @RequestParam topic: String,
        @RequestParam partition: Int,
        @RequestParam(defaultValue = "0") fromOffset: Long,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DeadLetterResponse> = reader.read(topic, partition, fromOffset, limit)
}
