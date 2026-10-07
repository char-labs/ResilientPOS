package com.resilientpos.owner.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException

/**
 * 비밀번호 정책. 15자(code point) 이상이고 UTF-8 72바이트 이하다. 72바이트는 bcrypt의 입력 한계다.
 * 조합 규칙은 두지 않는다. 평문은 이 객체도 저장하지 않는다.
 */
object OwnerPassword {
    private const val MIN_CODE_POINTS = 15
    private const val MAX_UTF8_BYTES = 72

    fun isValid(raw: String): Boolean =
        raw.codePointCount(0, raw.length) >= MIN_CODE_POINTS && raw.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES

    /** 정책을 못 맞추면 `INVALID_PASSWORD`다. */
    fun requireValid(raw: String) {
        if (!isValid(raw)) {
            throw DomainException(
                Owner.INVALID_PASSWORD,
                "비밀번호는 15자 이상이고 UTF-8 72바이트 이하여야 합니다",
                DomainErrorKind.INVALID,
            )
        }
    }
}
