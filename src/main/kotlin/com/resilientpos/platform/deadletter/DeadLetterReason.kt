package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainException

/** 컬럼 `t_dead_letter_redrive.reason`·`t_dead_letter_dismissal.reason varchar(500)`을 따라간다. 컬럼을 바꾸면 함께 바꾼다. */
private const val MAX_REASON_LENGTH = 500
private const val NUL = '\u0000'

/**
 * 재투입과 폐기가 같이 쓰는 운영자 사유 검사. 앞뒤 공백을 지운 값이 1~500자이고 NUL이 없으면 그 값을 돌려주고, 아니면 [errorCode]의 [DomainException](400)을 던진다.
 * NUL은 PostgreSQL varchar에 들어가지 못해 커밋에서 500이 된다(대조 case note와 같다).
 */
fun trimmedReasonOrThrow(reason: String, errorCode: String): String {
    val trimmed = reason.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_REASON_LENGTH || NUL in trimmed) {
        throw DomainException(errorCode, "사유는 앞뒤 공백을 지운 뒤 1~${MAX_REASON_LENGTH}자이고 NUL 문자가 없어야 합니다.")
    }
    return trimmed
}
