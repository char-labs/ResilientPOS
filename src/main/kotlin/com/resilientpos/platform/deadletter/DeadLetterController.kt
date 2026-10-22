package com.resilientpos.platform.deadletter

import com.resilientpos.platform.logger
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
 * 목록을 읽으면 감사 INFO 한 줄을 남긴다(여러 매장의 주문 ID·금액을 읽는 운영 행위다). 재투입과 폐기의 감사 로그는 [DeadLetterRedriver]·[DeadLetterDismisser]가 남긴다.
 */
@RestController
class DeadLetterController(
    private val reader: DeadLetterReader,
    private val redriver: DeadLetterRedriver,
    private val dismisser: DeadLetterDismisser,
) {

    @GetMapping("/admin/v1/dead-letters")
    fun list(
        @RequestParam topic: String,
        @RequestParam partition: Int,
        @RequestParam(defaultValue = "0") fromOffset: Long,
        @RequestParam(defaultValue = "50") limit: Int,
        @AuthenticationPrincipal operator: Jwt,
    ): List<DeadLetterResponse> {
        val subject = operator.subjectOrFail()
        return reader.read(topic, partition, fromOffset, limit).also {
            log.info { "audit action=DEAD_LETTERS_LISTED operator=$subject topic=$topic partition=$partition fromOffset=$fromOffset count=${it.size}" }
        }
    }

    @PostMapping("/admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive")
    fun redrive(
        @PathVariable topic: String,
        @PathVariable partition: Int,
        @PathVariable offset: Long,
        @RequestBody request: DeadLetterRedriveRequest,
        @AuthenticationPrincipal operator: Jwt,
    ): DeadLetterRedriveResponse = redriver.redrive(topic, partition, offset, request.reason.orEmpty(), operator.subjectOrFail())

    @PostMapping("/admin/v1/dead-letters/{topic}/{partition}/{offset}/dismiss")
    fun dismiss(
        @PathVariable topic: String,
        @PathVariable partition: Int,
        @PathVariable offset: Long,
        @RequestBody request: DeadLetterDismissRequest,
        @AuthenticationPrincipal operator: Jwt,
    ): DeadLetterDismissResponse = dismisser.dismiss(topic, partition, offset, request.reason.orEmpty(), operator.subjectOrFail())

    /**
     * resource server는 `sub`를 검사하지 않는다. 없거나 1~255자(OIDC Core, `operator_subject varchar(255)`)가 아니거나 제어 문자를 담은 `sub`는 IdP 설정 오류라
     * 읽지도 보내지도 않고 500으로 끝낸다. 보낸 뒤 기록 커밋에서 실패해 재요청마다 다시 보내는 일과 감사 줄 위조를 막는다. 메시지에 값을 싣지 않는다.
     */
    private fun Jwt.subjectOrFail(): String {
        val subject = checkNotNull(subject) { "운영자 토큰에 sub가 없다" }
        check(subject.length in 1..MAX_SUBJECT_LENGTH && subject.none(Char::isISOControl)) { "운영자 토큰의 sub가 1~${MAX_SUBJECT_LENGTH}자가 아니거나 제어 문자를 담았다" }
        return subject
    }

    companion object {
        private const val MAX_SUBJECT_LENGTH = 255
        private val log by logger()
    }
}
